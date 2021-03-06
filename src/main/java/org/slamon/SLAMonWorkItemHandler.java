package org.slamon;

import org.drools.core.process.instance.impl.WorkItemImpl;
import org.kie.api.runtime.process.WorkItem;
import org.kie.api.runtime.process.WorkItemHandler;
import org.kie.api.runtime.process.WorkItemManager;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * A custom WorkItemHandler implementation to handle SLAMon tasks
 * (or work items in jBPM language).
 */
public class SLAMonWorkItemHandler implements WorkItemHandler {

    static final Logger log = Logger.getLogger(Afm.class.getCanonicalName());

    private String mUrl = null;
    private String mTaskType = null;
    private int mTaskVersion = 0;
    private final Map<Long, String> mItemIdMap = new HashMap<Long, String>();

    /**
     * Construct SLAMonWorkItemHandler with specific AFM URL.
     */
    public SLAMonWorkItemHandler(String url) throws Exception {
        mUrl = url;
        if (mUrl == null) {
            throw new Exception("No AFM URL provided for SLAMonWorkItemHandler");
        }
    }

    /**
     * Construct SLAMonWorkItemHandler with system AFM URL.
     */
    public SLAMonWorkItemHandler() throws Exception {
        this(System.getProperty("slamon.afm.url"));
    }

    /**
     * Convenience overload to predefine task type and
     * task version so that these can be omitted in work item parameters.
     */
    public SLAMonWorkItemHandler(String taskType, int taskVersion) throws Exception {
        this();
        mTaskType = taskType;
        mTaskVersion = taskVersion;
    }

    /**
     * Convenience overload to predefine task type and
     * task version so that these can be omitted in work item parameters.
     */
    public SLAMonWorkItemHandler(String url, String taskType, int taskVersion) throws Exception {
        this(url);
        mTaskType = taskType;
        mTaskVersion = taskVersion;
    }

    public void executeWorkItem(final WorkItem workItem, final WorkItemManager manager) {

        Map<String, Object> input_params = workItem.getParameters();

        // First read all "generic" parameters from task, such as task type, version etc.
        // Then remove these entries from the map, leaving anything else behind.
        // left overs are then treated as task_data attributes.

        String taskType = mTaskType;
        if (taskType == null) {
            taskType = (String) input_params.remove("task_type");
        }

        int taskVersion = mTaskVersion;
        if (taskVersion == 0) {
            taskVersion = Integer.parseInt((String) input_params.remove("task_version"));
        }

        Task task;

        final String deploymentId = ((WorkItemImpl) workItem).getDeploymentId();

        try (EngineHolder engine = new EngineHolder(deploymentId)) {
            task = new Task(
                    Util.itemId(engine.getEngine(), workItem),
                    Util.processId(engine.getEngine(), workItem),
                    taskType,
                    taskVersion);
        }

        task.task_data = new HashMap<String, Object>();
        for (Map.Entry<String, Object> e : input_params.entrySet()) {
            Object value = Util.convertFromJBPM(e.getValue());
            task.task_data.put(e.getKey(), value);
            log.log(
                    Level.FINER,
                    "Adding parameter {0} for task {1}, value: {2}, type: {3}",
                    new Object[]{e.getKey(), task.task_id, value.toString(), value.getClass().getCanonicalName()}
            );
        }

        // Store local id for abortion, since
        // getting runtime manager in abortWorkItem seems to fail.
        final long localWorkItemId = workItem.getId();
        synchronized (mItemIdMap) {
            mItemIdMap.put(localWorkItemId, task.task_id);
        }

        Afm.get(mUrl).postTask(task, new Afm.ResultCallback() {

            @Override
            public void succeeded(Task task) {

                synchronized (mItemIdMap) {
                    mItemIdMap.remove(localWorkItemId);
                }

                try (EngineHolder engine = new EngineHolder(deploymentId)) {

                    HashMap<String, Object> results = new HashMap<String, Object>();
                    for (Map.Entry<String, Object> e : task.task_result.entrySet()) {
                        try {
                            Object value = e.getValue();
                            log.log(
                                    Level.FINER,
                                    "Adding OUTPUT parameter {0} for task {1}, value: {2}, type: {3}",
                                    new Object[]{e.getKey(), task.task_id, value.toString(), value.getClass().getCanonicalName()}
                            );
                            results.put(e.getKey(), Util.convertToJBPM(value));
                        } catch (Exception e2) {
                            log.log(Level.SEVERE, "Error during output variable conversions: {0}", e2.getMessage());
                        }
                    }
                    engine.getEngine().getKieSession().getWorkItemManager().completeWorkItem(workItem.getId(), results);
                }
            }

            @Override
            public void failed(Task task) {

                synchronized (mItemIdMap) {
                    mItemIdMap.remove(localWorkItemId);
                }

                log.log(Level.SEVERE, "A task returned as failed, aborting task {0}. Error: {1}", new Object[]{
                        task.task_id, task.task_error});

                try (EngineHolder engine = new EngineHolder(deploymentId)) {
                    engine.getEngine().getKieSession().getWorkItemManager().abortWorkItem(workItem.getId());
                }
            }
        });
    }

    public void abortWorkItem(WorkItem workItem, WorkItemManager manager) {
        // Get local id mapping to task UUID
        String taskId;
        synchronized (mItemIdMap) {
            taskId = mItemIdMap.remove(workItem.getId());
        }
        if (taskId != null) {
            Afm.get(mUrl).abortTask(taskId);
        }
        manager.abortWorkItem(workItem.getId());
    }
}

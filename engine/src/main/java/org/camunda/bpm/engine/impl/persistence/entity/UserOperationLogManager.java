/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.impl.persistence.entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.camunda.bpm.engine.EntityTypes;
import org.camunda.bpm.engine.history.UserOperationLogEntry;
import org.camunda.bpm.engine.impl.Page;
import org.camunda.bpm.engine.impl.UserOperationLogQueryImpl;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.history.event.HistoryEvent;
import org.camunda.bpm.engine.impl.history.event.UserOperationLogEntryEventEntity;
import org.camunda.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.camunda.bpm.engine.impl.history.producer.HistoryEventProducer;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.oplog.UserOperationLogContext;
import org.camunda.bpm.engine.impl.oplog.UserOperationLogContextEntry;
import org.camunda.bpm.engine.impl.oplog.UserOperationLogContextEntryBuilder;
import org.camunda.bpm.engine.impl.persistence.AbstractHistoricManager;

/**
 * Manager for {@link UserOperationLogEntryEventEntity} that also provides a generic and some specific log methods.
 *
 * @author Danny Gräf
 */
public class UserOperationLogManager extends AbstractHistoricManager {

  // LEGACY: task events are always written to user operation log
  protected static List<String> taskEvents = new ArrayList<String>();
  static {
    taskEvents.add(UserOperationLogEntry.OPERATION_TYPE_ADD_USER_LINK);
    taskEvents.add(UserOperationLogEntry.OPERATION_TYPE_ADD_USER_LINK);
    taskEvents.add(UserOperationLogEntry.OPERATION_TYPE_DELETE_USER_LINK);
    taskEvents.add(UserOperationLogEntry.OPERATION_TYPE_ADD_GROUP_LINK);
    taskEvents.add(UserOperationLogEntry.OPERATION_TYPE_DELETE_GROUP_LINK);
    taskEvents.add(UserOperationLogEntry.OPERATION_TYPE_ADD_ATTACHMENT);
    taskEvents.add(UserOperationLogEntry.OPERATION_TYPE_DELETE_ATTACHMENT);
  }

  public UserOperationLogEntry findOperationLogById(String entryId) {
    return getDbEntityManager().selectById(UserOperationLogEntryEventEntity.class, entryId);
  }

  public long findOperationLogEntryCountByQueryCriteria(UserOperationLogQueryImpl query) {
    getAuthorizationManager().configureUserOperationLogQuery(query);
    return (Long) getDbEntityManager().selectOne("selectUserOperationLogEntryCountByQueryCriteria", query);
  }

  @SuppressWarnings("unchecked")
  public List<UserOperationLogEntry> findOperationLogEntriesByQueryCriteria(UserOperationLogQueryImpl query, Page page) {
    getAuthorizationManager().configureUserOperationLogQuery(query);
    return getDbEntityManager().selectList("selectUserOperationLogEntriesByQueryCriteria", query, page);
  }

  public void deleteOperationLogEntryById(String entryId) {
    if (isHistoryLevelFullEnabled()) {
      getDbEntityManager().delete(UserOperationLogEntryEventEntity.class, "deleteUserOperationLogEntryById", entryId);
    }
  }

  protected void fireUserOperationLog(UserOperationLogContext context) {
    if (isHistoryLevelFullEnabled()) {

      context.setUserId(getAuthenticatedUserId());

      ProcessEngineConfigurationImpl configuration = Context.getProcessEngineConfiguration();

      HistoryEventProducer eventProducer = configuration.getHistoryEventProducer();
      HistoryEventHandler eventHandler = configuration.getHistoryEventHandler();

      List<HistoryEvent> historyEvents = eventProducer.createUserOperationLogEvents(context);
      eventHandler.handleEvents(historyEvents);
    }
  }

  public void logUserOperations(UserOperationLogContext context) {
    if (isHistoryLevelFullEnabled()) {

      if (!(isUserOperationLogEnabledOnCommandContext() && isUserAuthenticated()) && !isLegacyUserOperationLogEnabled()) {

        List<UserOperationLogContextEntry> entries = context.getEntries();
        Iterator<UserOperationLogContextEntry> iterator = entries.iterator();

        while(iterator.hasNext()) {
          UserOperationLogContextEntry entry = iterator.next();
          // log only task events
          if (!isTaskEvent(entry.getOperationType())) {
            iterator.remove();
          }
        }
      }

      fireUserOperationLog(context);

    }
  }

  public void logTaskOperations(String operation, TaskEntity task, List<PropertyChange> propertyChanges) {
    if (isUserOperationLogEnabled(operation)) {
      UserOperationLogContext context = new UserOperationLogContext();
      UserOperationLogContextEntryBuilder entryBuilder =
          UserOperationLogContextEntryBuilder.entry(operation, EntityTypes.TASK)
            .inContextOf(task, propertyChanges);

      context.addEntry(entryBuilder.create());
      fireUserOperationLog(context);
    }
  }

  public void logLinkOperation(String operation, TaskEntity task, PropertyChange propertyChange) {
    if (isUserOperationLogEnabled(operation)) {
      UserOperationLogContext context = new UserOperationLogContext();
      UserOperationLogContextEntryBuilder entryBuilder =
          UserOperationLogContextEntryBuilder.entry(operation, EntityTypes.IDENTITY_LINK)
            .inContextOf(task, Arrays.asList(propertyChange));

      context.addEntry(entryBuilder.create());
      fireUserOperationLog(context);
    }
  }

  public void logProcessInstanceOperation(String operation, String processInstanceId, String processDefinitionId, String processDefinitionKey, PropertyChange propertyChange) {
    if (isUserOperationLogEnabled(operation)) {

      UserOperationLogContext context = new UserOperationLogContext();
      UserOperationLogContextEntryBuilder entryBuilder =
          UserOperationLogContextEntryBuilder.entry(operation, EntityTypes.PROCESS_INSTANCE)
            .propertyChanges(propertyChange)
            .processInstanceId(processInstanceId)
            .processDefinitionId(processDefinitionId)
            .processDefinitionKey(processDefinitionKey);

      if(processInstanceId != null) {
        ExecutionEntity instance = getProcessInstanceManager().findExecutionById(processInstanceId);

        if (instance != null) {
          entryBuilder.inContextOf(instance);
        }
      }
      else if (processDefinitionId != null) {
        ProcessDefinitionEntity definition = getProcessDefinitionManager().findLatestProcessDefinitionById(processDefinitionId);
        if (definition != null) {
          entryBuilder.inContextOf(definition);
        }
      }

      context.addEntry(entryBuilder.create());
      fireUserOperationLog(context);
    }
  }

  public void logProcessDefinitionOperation(String operation, String processDefinitionId, String processDefinitionKey,
      PropertyChange propertyChange) {
    if (isUserOperationLogEnabled(operation)) {

      UserOperationLogContext context = new UserOperationLogContext();
      UserOperationLogContextEntryBuilder entryBuilder =
          UserOperationLogContextEntryBuilder.entry(operation, EntityTypes.PROCESS_DEFINITION)
            .propertyChanges(propertyChange)
            .processDefinitionId(processDefinitionId)
            .processDefinitionKey(processDefinitionKey);

      if (processDefinitionId != null) {
        ProcessDefinitionEntity definition = getProcessDefinitionManager().findLatestProcessDefinitionById(processDefinitionId);
        entryBuilder.inContextOf(definition);
      }

      context.addEntry(entryBuilder.create());

      fireUserOperationLog(context);
    }
  }

  public void logJobOperation(String operation, String jobId, String jobDefinitionId, String processInstanceId,
      String processDefinitionId, String processDefinitionKey, PropertyChange propertyChange) {
    if (isUserOperationLogEnabled(operation)) {

      UserOperationLogContext context = new UserOperationLogContext();
      UserOperationLogContextEntryBuilder entryBuilder =
          UserOperationLogContextEntryBuilder.entry(operation, EntityTypes.JOB)
            .jobId(jobId)
            .jobDefinitionId(jobDefinitionId)
            .processDefinitionId(processDefinitionId)
            .processDefinitionKey(processDefinitionKey)
            .propertyChanges(propertyChange);

      if(jobId != null) {
        JobEntity job = getJobManager().findJobById(jobId);
        // Backward compatibility
        if(job != null) {
          entryBuilder.inContextOf(job);
        }
      } else

      if(jobDefinitionId != null) {
        JobDefinitionEntity jobDefinition = getJobDefinitionManager().findById(jobDefinitionId);
        // Backward compatibility
        if(jobDefinition != null) {
          entryBuilder.inContextOf(jobDefinition);
        }
      }
      else if (processInstanceId != null) {
        ExecutionEntity processInstance = getProcessInstanceManager().findExecutionById(processInstanceId);
        // Backward compatibility
        if(processInstance != null) {
          entryBuilder.inContextOf(processInstance);
        }
      }
      else if (processDefinitionId != null) {
        ProcessDefinitionEntity definition = getProcessDefinitionManager().findLatestProcessDefinitionById(processDefinitionId);
        // Backward compatibility
        if(definition != null) {
          entryBuilder.inContextOf(definition);
        }
      }

      context.addEntry(entryBuilder.create());
      fireUserOperationLog(context);
    }
  }

  public void logJobDefinitionOperation(String operation, String jobDefinitionId, String processDefinitionId,
      String processDefinitionKey, PropertyChange propertyChange) {
    if(isUserOperationLogEnabled(operation)) {
      UserOperationLogContext context = new UserOperationLogContext();
      UserOperationLogContextEntryBuilder entryBuilder =
          UserOperationLogContextEntryBuilder.entry(operation, EntityTypes.JOB_DEFINITION)
            .jobDefinitionId(jobDefinitionId)
            .processDefinitionId(processDefinitionId)
            .processDefinitionKey(processDefinitionKey)
            .propertyChanges(propertyChange);

      if(jobDefinitionId != null) {
        JobDefinitionEntity jobDefinition = getJobDefinitionManager().findById(jobDefinitionId);
        // Backward compatibility
        if(jobDefinition != null) {
          entryBuilder.inContextOf(jobDefinition);
        }
      }
      else if (processDefinitionId != null) {
        ProcessDefinitionEntity definition = getProcessDefinitionManager().findLatestProcessDefinitionById(processDefinitionId);
        // Backward compatibility
        if(definition != null) {
          entryBuilder.inContextOf(definition);
        }
      }

      context.addEntry(entryBuilder.create());

      fireUserOperationLog(context);
    }
  }

  public void logAttachmentOperation(String operation, TaskEntity task, PropertyChange propertyChange) {
    if (isUserOperationLogEnabled(operation)) {
      UserOperationLogContext context = new UserOperationLogContext();

      UserOperationLogContextEntryBuilder entryBuilder =
          UserOperationLogContextEntryBuilder.entry(operation, EntityTypes.ATTACHMENT)
            .inContextOf(task, Arrays.asList(propertyChange));
      context.addEntry(entryBuilder.create());

      fireUserOperationLog(context);
    }
  }

  public void logVariableOperation(String operation, String executionId, String taskId, PropertyChange propertyChange) {
    if(isUserOperationLogEnabled(operation)) {

      UserOperationLogContext context = new UserOperationLogContext();

      UserOperationLogContextEntryBuilder entryBuilder =
          UserOperationLogContextEntryBuilder.entry(operation, EntityTypes.VARIABLE)
          .propertyChanges(propertyChange);

      if (executionId != null) {
        ExecutionEntity execution = getProcessInstanceManager().findExecutionById(executionId);
        entryBuilder.inContextOf(execution);
      }
      else if (taskId != null) {
        TaskEntity task = getTaskManager().findTaskById(taskId);
        entryBuilder.inContextOf(task, Arrays.asList(propertyChange));
      }

      context.addEntry(entryBuilder.create());
      fireUserOperationLog(context);
    }
  }

  public void logDeploymentOperation(String operation, String deploymentId, List<PropertyChange> propertyChanges) {
    if(isUserOperationLogEnabled(operation)) {

      UserOperationLogContext context = new UserOperationLogContext();

      UserOperationLogContextEntryBuilder entryBuilder =
          UserOperationLogContextEntryBuilder.entry(operation, EntityTypes.DEPLOYMENT)
          .deploymentId(deploymentId)
          .propertyChanges(propertyChanges);

      context.addEntry(entryBuilder.create());
      fireUserOperationLog(context);
    }

  }

  protected boolean isUserOperationLogEnabled(String operation) {
    return isHistoryLevelFullEnabled() &&
        (isLegacyUserOperationLogEnabled() || (isUserOperationLogEnabledOnCommandContext() && isUserAuthenticated()) || isTaskEvent(operation));
  }

  protected boolean isUserAuthenticated() {
    String userId = getAuthenticatedUserId();
    return userId != null && !userId.isEmpty();
  }

  protected String getAuthenticatedUserId() {
    CommandContext commandContext = Context.getCommandContext();
    return commandContext.getAuthenticatedUserId();
  }

  protected boolean isLegacyUserOperationLogEnabled() {
    ProcessEngineConfigurationImpl configuration = Context.getProcessEngineConfiguration();
    return configuration.isLegacyUserOperationLogEnabled();
  }

  protected boolean isUserOperationLogEnabledOnCommandContext() {
    return Context.getCommandContext().isUserOperationLogEnabled();
  }

  protected boolean isTaskEvent(String operation) {
    return taskEvents.contains(operation);
  }

}

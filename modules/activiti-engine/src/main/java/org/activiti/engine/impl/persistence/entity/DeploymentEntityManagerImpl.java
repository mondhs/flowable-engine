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

package org.activiti.engine.impl.persistence.entity;

import java.util.List;
import java.util.Map;

import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.EventDefinition;
import org.activiti.bpmn.model.Message;
import org.activiti.bpmn.model.MessageEventDefinition;
import org.activiti.bpmn.model.Signal;
import org.activiti.bpmn.model.SignalEventDefinition;
import org.activiti.bpmn.model.StartEvent;
import org.activiti.bpmn.model.TimerEventDefinition;
import org.activiti.engine.ProcessEngineConfiguration;
import org.activiti.engine.delegate.event.ActivitiEventType;
import org.activiti.engine.delegate.event.impl.ActivitiEventBuilder;
import org.activiti.engine.impl.DeploymentQueryImpl;
import org.activiti.engine.impl.ModelQueryImpl;
import org.activiti.engine.impl.Page;
import org.activiti.engine.impl.ProcessDefinitionQueryImpl;
import org.activiti.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.activiti.engine.impl.jobexecutor.TimerEventHandler;
import org.activiti.engine.impl.jobexecutor.TimerStartEventJobHandler;
import org.activiti.engine.impl.persistence.entity.data.DataManager;
import org.activiti.engine.impl.persistence.entity.data.DeploymentDataManager;
import org.activiti.engine.impl.util.CollectionUtil;
import org.activiti.engine.impl.util.ProcessDefinitionUtil;
import org.activiti.engine.impl.util.TimerUtil;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.Model;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.Job;

/**
 * @author Tom Baeyens
 * @author Joram Barrez
 */
public class DeploymentEntityManagerImpl extends AbstractEntityManager<DeploymentEntity> implements DeploymentEntityManager {

  protected DeploymentDataManager deploymentDataManager;
  
  public DeploymentEntityManagerImpl(ProcessEngineConfigurationImpl processEngineConfiguration, DeploymentDataManager deploymentDataManager) {
    super(processEngineConfiguration);
    this.deploymentDataManager = deploymentDataManager;
  }
  
  @Override
  protected DataManager<DeploymentEntity> getDataManager() {
    return deploymentDataManager;
  }
  
  @Override
  public void insert(DeploymentEntity deployment) {
    insert(deployment, false);

    for (ResourceEntity resource : deployment.getResources().values()) {
      resource.setDeploymentId(deployment.getId());
      getResourceEntityManager().insert(resource);
    }
  }

  @Override
  public void deleteDeployment(String deploymentId, boolean cascade) {
    List<ProcessDefinition> processDefinitions = new ProcessDefinitionQueryImpl().deploymentId(deploymentId).list();

    // Remove the deployment link from any model.
    // The model will still exists, as a model is a source for a deployment
    // model and has a different lifecycle
    List<Model> models = new ModelQueryImpl().deploymentId(deploymentId).list();
    for (Model model : models) {
      ModelEntity modelEntity = (ModelEntity) model;
      modelEntity.setDeploymentId(null);
      getModelEntityManager().updateModel(modelEntity);
    }

    if (cascade) {

      // delete process instances
      for (ProcessDefinition processDefinition : processDefinitions) {
        String processDefinitionId = processDefinition.getId();

        getExecutionEntityManager().deleteProcessInstancesByProcessDefinition(processDefinitionId, "deleted deployment", cascade);

      }
    }

    for (ProcessDefinition processDefinition : processDefinitions) {
      String processDefinitionId = processDefinition.getId();
      // remove related authorization parameters in IdentityLink table
      getIdentityLinkEntityManager().deleteIdentityLinksByProcDef(processDefinitionId);

      // event subscriptions
      EventSubscriptionEntityManager eventSubscriptionEntityManager = getEventSubscriptionEntityManager();
      List<EventSubscriptionEntity> eventSubscriptionEntities = eventSubscriptionEntityManager  
          .findEventSubscriptionsByTypeAndProcessDefinitionId(null, processDefinitionId, processDefinition.getTenantId()); // null type ==> all types
      for (EventSubscriptionEntity eventSubscriptionEntity : eventSubscriptionEntities) {
        eventSubscriptionEntityManager.delete(eventSubscriptionEntity);
      }
      
      getProcessDefinitionInfoEntityManager().deleteProcessDefinitionInfo(processDefinitionId);
    }

    // delete process definitions from db
    getProcessDefinitionEntityManager().deleteProcessDefinitionsByDeploymentId(deploymentId);

    for (ProcessDefinition processDefinition : processDefinitions) {

      // remove timer start events for current process definition:
      
      List<Job> timerStartJobs = getJobEntityManager()
          .findJobsByTypeAndProcessDefinitionId(TimerStartEventJobHandler.TYPE, processDefinition.getId());
      if (timerStartJobs != null && timerStartJobs.size() > 0) {
        for (Job timerStartJob : timerStartJobs) {
          if (getEventDispatcher().isEnabled()) {
            getEventDispatcher().dispatchEvent(ActivitiEventBuilder.createEntityEvent(ActivitiEventType.JOB_CANCELED, timerStartJob, null, null, processDefinition.getId()));
          }

          getJobEntityManager().delete((JobEntity) timerStartJob);
        }
      }
      
      // If previous process definition version has a timer start event, it must be added
      ProcessDefinitionEntity latestProcessDefinition = findLatestProcessDefinition(processDefinition);

      // Only if the currently deleted process definition is the latest version, we fall back to the previous timer start event
      if (processDefinition.getId().equals(latestProcessDefinition.getId())) { 
        
        // Try to find a previous version (it could be some versions are missing due to deletions)
        ProcessDefinition previousProcessDefinition = findNewLatestProcessDefinitionAfterRemovalOf(processDefinition);
        
        if (previousProcessDefinition != null) {

          BpmnModel bpmnModel = ProcessDefinitionUtil.getBpmnModel(previousProcessDefinition.getId());
          org.activiti.bpmn.model.Process previousProcess = ProcessDefinitionUtil.getProcess(previousProcessDefinition.getId());
          if (CollectionUtil.isNotEmpty(previousProcess.getFlowElements())) {
            
            List<StartEvent> startEvents = previousProcess.findFlowElementsOfType(StartEvent.class);
            
            if (CollectionUtil.isNotEmpty(startEvents)) {
              for (StartEvent startEvent : startEvents) {
                
                if (CollectionUtil.isNotEmpty(startEvent.getEventDefinitions())) {
                  EventDefinition eventDefinition = startEvent.getEventDefinitions().get(0);
                  if (eventDefinition instanceof TimerEventDefinition) {
                    
                    TimerEventDefinition timerEventDefinition = (TimerEventDefinition) eventDefinition;
                    TimerEntity timer = TimerUtil.createTimerEntityForTimerEventDefinition((TimerEventDefinition) eventDefinition, false, null, TimerStartEventJobHandler.TYPE,
                        TimerEventHandler.createConfiguration(startEvent.getId(), timerEventDefinition.getEndDate()));
                    
                    if (timer != null) {
                      timer.setProcessDefinitionId(previousProcessDefinition.getId());
  
                      if (previousProcessDefinition.getTenantId() != null) {
                        timer.setTenantId(previousProcessDefinition.getTenantId());
                      }
  
                      getJobEntityManager().schedule(timer);
                    }
                    
                  } else if (eventDefinition instanceof SignalEventDefinition) {
                    
                    SignalEventDefinition signalEventDefinition = (SignalEventDefinition) eventDefinition;
                    SignalEventSubscriptionEntity subscriptionEntity = getEventSubscriptionEntityManager().createSignalEventSubscription();
                    Signal signal = bpmnModel.getSignal(signalEventDefinition.getSignalRef());
                    if (signal != null) {
                      subscriptionEntity.setEventName(signal.getName());
                    } else {
                      subscriptionEntity.setEventName(signalEventDefinition.getSignalRef());
                    }
                    subscriptionEntity.setActivityId(startEvent.getId());
                    subscriptionEntity.setProcessDefinitionId(previousProcessDefinition.getId());
                    if (previousProcessDefinition.getTenantId() != null) {
                      subscriptionEntity.setTenantId(previousProcessDefinition.getTenantId());
                    }

                    getEventSubscriptionEntityManager().insert(subscriptionEntity);
                    
                  } else if (eventDefinition instanceof MessageEventDefinition) {
                    
                    MessageEventDefinition messageEventDefinition = (MessageEventDefinition) eventDefinition;
                    if (bpmnModel.containsMessageId(messageEventDefinition.getMessageRef())) {
                      Message message = bpmnModel.getMessage(messageEventDefinition.getMessageRef());
                      messageEventDefinition.setMessageRef(message.getName());
                    }

                    MessageEventSubscriptionEntity newSubscription = getEventSubscriptionEntityManager().createMessageEventSubscription();
                    newSubscription.setEventName(messageEventDefinition.getMessageRef());
                    newSubscription.setActivityId(startEvent.getId());
                    newSubscription.setConfiguration(previousProcessDefinition.getId());
                    newSubscription.setProcessDefinitionId(previousProcessDefinition.getId());

                    if (previousProcessDefinition.getTenantId() != null) {
                      newSubscription.setTenantId(previousProcessDefinition.getTenantId());
                    }

                    getEventSubscriptionEntityManager().insert(newSubscription);
                    
                  }
                  
                }
                
              }
            }

          }

        }
      }

    }

    getResourceEntityManager().deleteResourcesByDeploymentId(deploymentId);

    delete(findById(deploymentId), false);
  }
  
  protected ProcessDefinitionEntity findLatestProcessDefinition(ProcessDefinition processDefinition) {
    ProcessDefinitionEntity latestProcessDefinition = null;
    if (processDefinition.getTenantId() != null && !ProcessEngineConfiguration.NO_TENANT_ID.equals(processDefinition.getTenantId())) {
      latestProcessDefinition = getProcessDefinitionEntityManager()
          .findLatestProcessDefinitionByKeyAndTenantId(processDefinition.getKey(), processDefinition.getTenantId());
    } else {
      latestProcessDefinition = getProcessDefinitionEntityManager()
          .findLatestProcessDefinitionByKey(processDefinition.getKey());
    }
    return latestProcessDefinition;
  }
  
  protected ProcessDefinition findNewLatestProcessDefinitionAfterRemovalOf(ProcessDefinition processDefinitionToBeRemoved) {
    
    // The latest process definition is not necessarily the one with 'version -1' (some versions could have been deleted)
    // Hence, the following logic
    
    ProcessDefinitionQueryImpl query = new ProcessDefinitionQueryImpl();
    query.processDefinitionKey(processDefinitionToBeRemoved.getKey());
    
    if (processDefinitionToBeRemoved.getTenantId() != null 
        && !ProcessEngineConfiguration.NO_TENANT_ID.equals(processDefinitionToBeRemoved.getTenantId())) {
      query.processDefinitionTenantId(processDefinitionToBeRemoved.getTenantId());
    } else {
      query.processDefinitionWithoutTenantId();
    }
    
    query.processDefinitionVersionLowerThan(processDefinitionToBeRemoved.getVersion());
    query.orderByProcessDefinitionVersion().desc();
    
    List<ProcessDefinition> processDefinitions = getProcessDefinitionEntityManager().findProcessDefinitionsByQueryCriteria(query, new Page(0, 1));
    if (processDefinitions != null && processDefinitions.size() > 0) {
      return processDefinitions.get(0);
    } 
    return null;
  }

  @Override
  public DeploymentEntity findLatestDeploymentByName(String deploymentName) {
    return deploymentDataManager.findLatestDeploymentByName(deploymentName);
  }

  @Override
  public long findDeploymentCountByQueryCriteria(DeploymentQueryImpl deploymentQuery) {
    return deploymentDataManager.findDeploymentCountByQueryCriteria(deploymentQuery);
  }

  @Override
  public List<Deployment> findDeploymentsByQueryCriteria(DeploymentQueryImpl deploymentQuery, Page page) {
    return deploymentDataManager.findDeploymentsByQueryCriteria(deploymentQuery, page);
  }

  @Override
  public List<String> getDeploymentResourceNames(String deploymentId) {
    return deploymentDataManager.getDeploymentResourceNames(deploymentId);
  }

  @Override
  public List<Deployment> findDeploymentsByNativeQuery(Map<String, Object> parameterMap, int firstResult, int maxResults) {
    return deploymentDataManager.findDeploymentsByNativeQuery(parameterMap, firstResult, maxResults);
  }

  @Override
  public long findDeploymentCountByNativeQuery(Map<String, Object> parameterMap) {
    return deploymentDataManager.findDeploymentCountByNativeQuery(parameterMap);
  }


  public DeploymentDataManager getDeploymentDataManager() {
    return deploymentDataManager;
  }


  public void setDeploymentDataManager(DeploymentDataManager deploymentDataManager) {
    this.deploymentDataManager = deploymentDataManager;
  }
  
}

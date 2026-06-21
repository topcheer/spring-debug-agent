package com.demo.flowable;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Deploys and starts a Flowable BPMN process at startup.
 * The process is defined in resources/processes/order-approval.bpmn20.xml.
 */
@Component
public class FlowableProcessSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FlowableProcessSeeder.class);

    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;

    @Override
    public void run(String... args) {
        // Deploy the process
        Deployment deployment = repositoryService.createDeployment()
                .name("Order Approval Process")
                .addClasspathResource("processes/order-approval.bpmn20.xml")
                .deploy();
        log.info("Flowable: deployed process, deploymentId={}", deployment.getId());

        // Start a process instance
        Map<String, Object> variables = new HashMap<>();
        variables.put("customer", "Alice Zhang");
        variables.put("amount", 5000);
        variables.put("requester", "demo-user");
        org.flowable.engine.runtime.ProcessInstance pi =
                runtimeService.startProcessInstanceByKey("orderApproval", "ORD-001", variables);
        log.info("Flowable: started process instance, pid={}, businessKey={}", pi.getId(), pi.getBusinessKey());

        // Complete the first task (manager approval)
        Task task = taskService.createTaskQuery()
                .processInstanceId(pi.getId())
                .singleResult();
        if (task != null) {
            log.info("Flowable: first task = '{}', assignee='{}'", task.getName(), task.getAssignee());
            taskService.complete(task.getId());
            log.info("Flowable: completed task '{}'", task.getName());
        }
    }
}

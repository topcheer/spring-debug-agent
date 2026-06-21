package com.demo.flowable;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Flowable JavaDelegate for auto-approval service task.
 */
@Component("autoApproveDelegate")
public class AutoApproveDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(AutoApproveDelegate.class);

    @Override
    public void execute(DelegateExecution execution) {
        String customer = (String) execution.getVariable("customer");
        Object amount = execution.getVariable("amount");
        log.info("Flowable: auto-approving order for {}, amount={}", customer, amount);
        execution.setVariable("approved", true);
        execution.setVariable("approvedBy", "auto-approve");
    }
}

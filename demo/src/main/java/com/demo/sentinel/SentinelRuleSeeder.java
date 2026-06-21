package com.demo.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds Sentinel flow control and degrade rules at startup.
 */
@Component
public class SentinelRuleSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SentinelRuleSeeder.class);

    @Override
    public void run(String... args) {
        // Flow control rule: limit queryOrder to 10 QPS
        List<FlowRule> flowRules = new ArrayList<>();
        FlowRule queryOrderRule = new FlowRule();
        queryOrderRule.setResource("queryOrder");
        queryOrderRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        queryOrderRule.setCount(10);
        queryOrderRule.setLimitApp("default");
        flowRules.add(queryOrderRule);

        FlowRule callPaymentRule = new FlowRule();
        callPaymentRule.setResource("callPayment");
        callPaymentRule.setGrade(RuleConstant.FLOW_GRADE_THREAD);
        callPaymentRule.setCount(5);
        callPaymentRule.setLimitApp("default");
        flowRules.add(callPaymentRule);

        FlowRuleManager.loadRules(flowRules);

        // Degrade rule: trip circuit for callPayment after 3 exceptions
        List<DegradeRule> degradeRules = new ArrayList<>();
        DegradeRule paymentDegrade = new DegradeRule();
        paymentDegrade.setResource("callPayment");
        paymentDegrade.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT);
        paymentDegrade.setCount(3);
        paymentDegrade.setTimeWindow(10);
        paymentDegrade.setMinRequestAmount(5);
        degradeRules.add(paymentDegrade);

        DegradeRuleManager.loadRules(degradeRules);

        log.info("Sentinel demo: loaded {} flow rules, {} degrade rules", flowRules.size(), degradeRules.size());
    }
}

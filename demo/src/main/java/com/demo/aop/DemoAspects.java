package com.demo.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * Demo AOP aspects for AopInspector.
 * Provides realistic @Before, @AfterReturning, @AfterThrowing, and @Around advice.
 */
@Configuration
@Aspect
public class DemoAspects {

    private static final Logger log = LoggerFactory.getLogger(DemoAspects.class);

    @Pointcut("execution(* com.demo.service.OrderService.*(..))")
    public void orderServiceMethods() {}

    @Pointcut("execution(* com.demo.service.PricingService.*(..))")
    public void pricingServiceMethods() {}

    @Pointcut("execution(* com.demo.repository..*(..))")
    public void repositoryMethods() {}

    @Before("orderServiceMethods()")
    public void logBeforeOrderOperation() {
        log.debug("AOP: OrderService method invoked");
    }

    @AfterReturning(pointcut = "orderServiceMethods()", returning = "result")
    public void logAfterOrderSuccess(Object result) {
        log.debug("AOP: OrderService method returned: {}",
                result != null ? result.getClass().getSimpleName() : "void");
    }

    @AfterThrowing(pointcut = "orderServiceMethods()", throwing = "ex")
    public void logOrderException(Exception ex) {
        log.warn("AOP: OrderService threw exception: {}", ex.getMessage());
    }

    @Around("pricingServiceMethods()")
    public Object measurePricingLatency(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > 50) {
                log.warn("AOP: Slow pricing operation: {} took {}ms", pjp.getSignature().getName(), elapsed);
            }
            return result;
        } catch (Throwable t) {
            log.error("AOP: Pricing operation failed: {}", t.getMessage());
            throw t;
        }
    }

    @Around("repositoryMethods()")
    public Object measureRepositoryLatency(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = pjp.proceed();
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > 100) {
            log.warn("AOP: Slow repository operation: {} took {}ms, args={}",
                    pjp.getSignature().getName(), elapsed,
                    Arrays.toString(pjp.getArgs()));
        }
        return result;
    }
}

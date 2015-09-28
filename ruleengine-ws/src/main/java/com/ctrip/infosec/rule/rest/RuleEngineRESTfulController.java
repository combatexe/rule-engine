/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.ctrip.infosec.rule.rest;

import com.ctrip.infosec.common.Constants;
import com.ctrip.infosec.common.model.RiskFact;
import com.ctrip.infosec.configs.rule.trace.logger.TraceLogger;
import com.ctrip.infosec.configs.rulemonitor.RuleMonitorHelper;
import com.ctrip.infosec.configs.rulemonitor.RuleMonitorType;

import static com.ctrip.infosec.configs.utils.EventBodyUtils.valueAsInt;
import com.ctrip.infosec.rule.Contexts;
import com.ctrip.infosec.rule.executor.PostRulesExecutorService;
import com.ctrip.infosec.rule.executor.PreRulesExecutorService;
import com.ctrip.infosec.rule.executor.EventDataMergeService;
import com.ctrip.infosec.rule.executor.RulesExecutorService;
import com.ctrip.infosec.rule.executor.WhiteListRulesExecutorService;
import com.ctrip.infosec.sars.monitor.SarsMonitorContext;
import com.meidusa.fastjson.JSON;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 规则同步执行接口
 *
 * @author zhengby
 */
@Controller
@RequestMapping(value = "/rule")
public class RuleEngineRESTfulController {

    private static Logger logger = LoggerFactory.getLogger(RuleEngineRESTfulController.class);
    @Autowired
    private WhiteListRulesExecutorService whiteListRulesExecutorService;
    @Autowired
    private RulesExecutorService rulesExecutorService;
    @Autowired
    private PreRulesExecutorService preRulesExecutorService;
    @Autowired
    private EventDataMergeService eventDataMergeService;
    @Autowired
    private PostRulesExecutorService postRulesExecutorService;

    @RequestMapping(value = "/verify", method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<?> verify(@RequestBody String factTxt) {
        logger.info("REST: fact=" + factTxt);
        RiskFact fact = JSON.parseObject(factTxt, RiskFact.class);
        Contexts.setAsync(false);
        Contexts.setLogPrefix("[" + fact.eventPoint + "][" + fact.eventId + "] ");
        SarsMonitorContext.setLogPrefix(Contexts.getLogPrefix());

        boolean traceLoggerEnabled = MapUtils.getBoolean(fact.ext, Constants.key_traceLogger, true);
        TraceLogger.enabled(traceLoggerEnabled);

        RuleMonitorHelper.newTrans(fact, RuleMonitorType.CP_SYNC);

        // 引入节点编号优化排序
        // S0 - 接入层同步前
        // S1 - 同步引擎
        // S2 - 接入层同步后
        // S3 - 异步引擎
        try {
            // 执行数据合并（GET）
            try {
                RuleMonitorHelper.newTrans(fact, RuleMonitorType.GET);
                TraceLogger.beginTrans(fact.eventId, "S1");
                TraceLogger.setLogPrefix("[同步数据合并]");
                eventDataMergeService.executeRedisGet(fact);
            } finally {
                TraceLogger.commitTrans();
                RuleMonitorHelper.commitTrans(fact);
            }
            // 执行预处理            
            try {
                RuleMonitorHelper.newTrans(fact, RuleMonitorType.PRE_RULE_WRAP);
                TraceLogger.beginTrans(fact.eventId, "S1");
                TraceLogger.setLogPrefix("[同步预处理]");
                preRulesExecutorService.executePreRules(fact, false);
            } finally {
                TraceLogger.commitTrans();
                RuleMonitorHelper.commitTrans(fact);
            }
            // 执行白名单规则
            try {
                RuleMonitorHelper.newTrans(fact, RuleMonitorType.WB_RULE_WRAP);
                TraceLogger.beginTrans(fact.eventId, "S1");
                TraceLogger.setLogPrefix("[黑白名单规则]");
                whiteListRulesExecutorService.executeWhitelistRules(fact);
            } finally {
                TraceLogger.commitTrans();
                RuleMonitorHelper.commitTrans(fact);
            }

            // 是否中白名单标志
            boolean matchedWhitelist = false;
            // 非适配接入点、中白名单"0"的直接返回
            if (!Constants.eventPointsWithScene.contains(fact.eventPoint)) {
                if (fact.finalWhitelistResult != null && fact.finalWhitelistResult.containsKey(Constants.riskLevel)) {

                    int finalWhitelistRiskLevel = valueAsInt(fact.finalWhitelistResult, Constants.riskLevel);
                    if (finalWhitelistRiskLevel == 0 || finalWhitelistRiskLevel == 95) {
                        fact.finalResult.put(Constants.riskLevel, finalWhitelistRiskLevel);
                        fact.finalResult.put(Constants.riskMessage, "命中白名单规则[0]");
                        matchedWhitelist = true;
                    } else if (finalWhitelistRiskLevel >= 200) {
                        fact.finalResult.put(Constants.riskLevel, finalWhitelistRiskLevel);
                        fact.finalResult.put(Constants.riskMessage, "命中黑名单规则[" + finalWhitelistRiskLevel + "]");
                        matchedWhitelist = true;
                    }
                }
            }

            // 执行同步规则
            if (!matchedWhitelist) {
                try {
                    RuleMonitorHelper.newTrans(fact, RuleMonitorType.RULE_WRAP);
                    TraceLogger.beginTrans(fact.eventId, "S1");
                    TraceLogger.setLogPrefix("[同步规则]");
                    rulesExecutorService.executeSyncRules(fact);
                } finally {
                    TraceLogger.commitTrans();
                    RuleMonitorHelper.commitTrans(fact);
                }
            }
            // 执行后处理
            try {
                RuleMonitorHelper.newTrans(fact, RuleMonitorType.POST_RULE_WRAP);
                TraceLogger.beginTrans(fact.eventId, "S1");
                TraceLogger.setLogPrefix("[同步后处理]");
                postRulesExecutorService.executePostRules(fact, false);
            } finally {
                TraceLogger.commitTrans();
                RuleMonitorHelper.commitTrans(fact);
            }
            // 执行数据合并（PUT）
            try {
                RuleMonitorHelper.newTrans(fact, RuleMonitorType.PUT);
                TraceLogger.beginTrans(fact.eventId, "S1");
                TraceLogger.setLogPrefix("[同步数据合并]");
                eventDataMergeService.executeRedisPut(fact);
            } finally {
                TraceLogger.commitTrans();
                RuleMonitorHelper.commitTrans(fact);
            }

        } catch (Throwable ex) {
            if (fact.finalResult == null) {
                fact.setFinalResult(Constants.defaultResult);
            }
            logger.error(Contexts.getLogPrefix() + "invoke query exception.", ex);

            RuleMonitorHelper.setFault(ex);
        } finally {
            RuleMonitorHelper.commitTrans(fact);
        }

        return new ResponseEntity(fact, HttpStatus.OK);
    }

}

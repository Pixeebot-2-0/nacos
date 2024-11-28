/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package com.alibaba.nacos.config.server.service;

import com.alibaba.nacos.api.remote.RpcScheduledExecutor;
import com.alibaba.nacos.common.notify.Event;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.notify.listener.Subscriber;
import com.alibaba.nacos.common.utils.ExceptionUtil;
import com.alibaba.nacos.config.server.model.SampleResult;
import com.alibaba.nacos.config.server.model.event.LocalDataChangeEvent;
import com.alibaba.nacos.config.server.monitor.MetricsMonitor;
import com.alibaba.nacos.config.server.utils.ConfigExecutor;
import com.alibaba.nacos.config.server.utils.GroupKey;
import com.alibaba.nacos.config.server.utils.LogUtil;
import com.alibaba.nacos.config.server.utils.MD5Util;
import com.alibaba.nacos.config.server.utils.RequestUtil;
import com.alibaba.nacos.plugin.control.ControlManagerCenter;
import com.alibaba.nacos.plugin.control.connection.request.ConnectionCheckRequest;
import com.alibaba.nacos.plugin.control.connection.response.ConnectionCheckResponse;
import java.security.SecureRandom;
import org.springframework.stereotype.Service;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.alibaba.nacos.config.server.utils.LogUtil.MEMORY_LOG;
import static com.alibaba.nacos.config.server.utils.LogUtil.PULL_LOG;

/**
 * LongPollingService.
 *
 * @author Nacos
 */
@Service
public class LongPollingService {
    
    private static final int SAMPLE_PERIOD = 100;
    
    private static final int SAMPLE_TIMES = 3;
    
    private static final String TRUE_STR = "true";
    
    private Map<String, Long> retainIps = new ConcurrentHashMap<>();
    
    public SampleResult getSubscribleInfo(String dataId, String group, String tenant) {
        String groupKey = GroupKey.getKeyTenant(dataId, group, tenant);
        SampleResult sampleResult = new SampleResult();
        Map<String, String> lisentersGroupkeyStatus = new HashMap<>(50);
        
        for (ClientLongPolling clientLongPolling : allSubs) {
            if (clientLongPolling.clientMd5Map.containsKey(groupKey)) {
                lisentersGroupkeyStatus.put(clientLongPolling.ip, clientLongPolling.clientMd5Map.get(groupKey));
            }
        }
        sampleResult.setLisentersGroupkeyStatus(lisentersGroupkeyStatus);
        return sampleResult;
    }
    
    public SampleResult getSubscribleInfoByIp(String clientIp) {
        SampleResult sampleResult = new SampleResult();
        Map<String, String> lisentersGroupkeyStatus = new HashMap<>(50);
        
        for (ClientLongPolling clientLongPolling : allSubs) {
            if (clientLongPolling.ip.equals(clientIp)) {
                // One ip can have multiple listener.
                if (!lisentersGroupkeyStatus.equals(clientLongPolling.clientMd5Map)) {
                    lisentersGroupkeyStatus.putAll(clientLongPolling.clientMd5Map);
                }
            }
        }
        sampleResult.setLisentersGroupkeyStatus(lisentersGroupkeyStatus);
        return sampleResult;
    }
    
    /**
     * Aggregate the sampling IP and monitoring configuration information in the sampling results. There is no problem
     * for the merging strategy to cover the previous one with the latter.
     *
     * @param sampleResults sample Results.
     * @return Results.
     */
    public SampleResult mergeSampleResult(List<SampleResult> sampleResults) {
        SampleResult mergeResult = new SampleResult();
        Map<String, String> lisentersGroupkeyStatus = new HashMap<>(50);
        for (SampleResult sampleResult : sampleResults) {
            Map<String, String> lisentersGroupkeyStatusTmp = sampleResult.getLisentersGroupkeyStatus();
            for (Map.Entry<String, String> entry : lisentersGroupkeyStatusTmp.entrySet()) {
                lisentersGroupkeyStatus.put(entry.getKey(), entry.getValue());
            }
        }
        mergeResult.setLisentersGroupkeyStatus(lisentersGroupkeyStatus);
        return mergeResult;
    }
    
    public SampleResult getCollectSubscribleInfo(String dataId, String group, String tenant) {
        List<SampleResult> sampleResultLst = new ArrayList<>(50);
        for (int i = 0; i < SAMPLE_TIMES; i++) {
            SampleResult sampleTmp = getSubscribleInfo(dataId, group, tenant);
            if (sampleTmp != null) {
                sampleResultLst.add(sampleTmp);
            }
            if (i < SAMPLE_TIMES - 1) {
                try {
                    Thread.sleep(SAMPLE_PERIOD);
                } catch (InterruptedException e) {
                    LogUtil.CLIENT_LOG.error("sleep wrong", e);
                }
            }
        }
        
        return mergeSampleResult(sampleResultLst);
    }
    
    public SampleResult getCollectSubscribleInfoByIp(String ip) {
        SampleResult sampleResult = new SampleResult();
        sampleResult.setLisentersGroupkeyStatus(new HashMap<String, String>(50));
        for (int i = 0; i < SAMPLE_TIMES; i++) {
            SampleResult sampleTmp = getSubscribleInfoByIp(ip);
            if (sampleTmp != null) {
                if (sampleTmp.getLisentersGroupkeyStatus() != null && !sampleResult.getLisentersGroupkeyStatus()
                        .equals(sampleTmp.getLisentersGroupkeyStatus())) {
                    sampleResult.getLisentersGroupkeyStatus().putAll(sampleTmp.getLisentersGroupkeyStatus());
                }
            }
            if (i < SAMPLE_TIMES - 1) {
                try {
                    Thread.sleep(SAMPLE_PERIOD);
                } catch (InterruptedException e) {
                    LogUtil.CLIENT_LOG.error("sleep wrong", e);
                }
            }
        }
        return sampleResult;
    }
    
    /**
     * Add LongPollingClient.
     *
     * @param req              HttpServletRequest.
     * @param rsp              HttpServletResponse.
     * @param clientMd5Map     clientMd5Map.
     * @param probeRequestSize probeRequestSize.
     */
    public void addLongPollingClient(HttpServletRequest req, HttpServletResponse rsp, Map<String, String> clientMd5Map,
            int probeRequestSize) {
        
        String noHangUpFlag = req.getHeader(LongPollingService.LONG_POLLING_NO_HANG_UP_HEADER);
        
        long start = System.currentTimeMillis();
        List<String> changedGroups = MD5Util.compareMd5(req, rsp, clientMd5Map);
        if (changedGroups.size() > 0) {
            generateResponse(req, rsp, changedGroups);
            LogUtil.CLIENT_LOG.info("{}|{}|{}|{}|{}|{}|{}", System.currentTimeMillis() - start, "instant",
                    RequestUtil.getRemoteIp(req), "polling", clientMd5Map.size(), probeRequestSize,
                    changedGroups.size());
            return;
        } else if (noHangUpFlag != null && noHangUpFlag.equalsIgnoreCase(TRUE_STR)) {
            LogUtil.CLIENT_LOG.info("{}|{}|{}|{}|{}|{}|{}", System.currentTimeMillis() - start, "nohangup",
                    RequestUtil.getRemoteIp(req), "polling", clientMd5Map.size(), probeRequestSize,
                    changedGroups.size());
            return;
        }
        
        // Must be called by http thread, or send response.
        final AsyncContext asyncContext = req.startAsync();
        // AsyncContext.setTimeout() is incorrect, Control by oneself
        asyncContext.setTimeout(0L);
        String ip = RequestUtil.getRemoteIp(req);
        ConnectionCheckResponse connectionCheckResponse = checkLimit(req);
        if (!connectionCheckResponse.isSuccess()) {
            RpcScheduledExecutor.CONTROL_SCHEDULER.schedule(
                    () -> generate503Response(asyncContext, rsp, connectionCheckResponse.getMessage()),
                    1000L + new SecureRandom().nextInt(2000), TimeUnit.MILLISECONDS);
            return;
        }
        
        String appName = req.getHeader(RequestUtil.CLIENT_APPNAME_HEADER);
        String tag = req.getHeader("Vipserver-Tag");
        int delayTime = SwitchService.getSwitchInteger(SwitchService.FIXED_DELAY_TIME, 500);
        int minLongPoolingTimeout = SwitchService.getSwitchInteger("MIN_LONG_POOLING_TIMEOUT", 10000);
        
        // Add delay time for LoadBalance, and one response is returned 500 ms in advance to avoid client timeout.
        String requestLongPollingTimeOut = req.getHeader(LongPollingService.LONG_POLLING_HEADER);
        long timeout = Math.max(minLongPoolingTimeout, Long.parseLong(requestLongPollingTimeOut) - delayTime);
        ConfigExecutor.executeLongPolling(
                new ClientLongPolling(asyncContext, clientMd5Map, ip, probeRequestSize, timeout, appName, tag));
    }
    
    private ConnectionCheckResponse checkLimit(HttpServletRequest httpServletRequest) {
        String ip = RequestUtil.getRemoteIp(httpServletRequest);
        String appName = httpServletRequest.getHeader(RequestUtil.CLIENT_APPNAME_HEADER);
        ConnectionCheckRequest connectionCheckRequest = new ConnectionCheckRequest(ip, appName, "LongPolling");
        ConnectionCheckResponse checkResponse = ControlManagerCenter.getInstance().getConnectionControlManager()
                .check(connectionCheckRequest);
        return checkResponse;
    }
    
    public static boolean isSupportLongPolling(HttpServletRequest req) {
        return null != req.getHeader(LONG_POLLING_HEADER);
    }
    
    @SuppressWarnings("PMD.ThreadPoolCreationRule")
    public LongPollingService() {
        allSubs = new ConcurrentLinkedQueue<>();
        
        ConfigExecutor.scheduleLongPolling(new StatTask(), 0L, 10L, TimeUnit.SECONDS);
        
        // Register LocalDataChangeEvent to NotifyCenter.
        NotifyCenter.registerToPublisher(LocalDataChangeEvent.class, NotifyCenter.ringBufferSize);
        
        // Register A Subscriber to subscribe LocalDataChangeEvent.
        NotifyCenter.registerSubscriber(new Subscriber() {
            
            @Override
            public void onEvent(Event event) {
                if (event instanceof LocalDataChangeEvent) {
                    LocalDataChangeEvent evt = (LocalDataChangeEvent) event;
                    ConfigExecutor.executeLongPolling(new DataChangeTask(evt.groupKey));
                }
                
            }
            
            @Override
            public Class<? extends Event> subscribeType() {
                return LocalDataChangeEvent.class;
            }
        });
        
    }
    
    public static final String LONG_POLLING_HEADER = "Long-Pulling-Timeout";
    
    public static final String LONG_POLLING_NO_HANG_UP_HEADER = "Long-Pulling-Timeout-No-Hangup";
    
    /**
     * ClientLongPolling subscibers.
     */
    final Queue<ClientLongPolling> allSubs;
    
    class DataChangeTask implements Runnable {
        
        @Override
        public void run() {
            try {
                for (Iterator<ClientLongPolling> iter = allSubs.iterator(); iter.hasNext(); ) {
                    ClientLongPolling clientSub = iter.next();
                    if (clientSub.clientMd5Map.containsKey(groupKey)) {
                        
                        getRetainIps().put(clientSub.ip, System.currentTimeMillis());
                        iter.remove(); // Delete subscribers' relationships.
                        LogUtil.CLIENT_LOG.info("{}|{}|{}|{}|{}|{}|{}", (System.currentTimeMillis() - changeTime),
                                "in-advance",
                                RequestUtil.getRemoteIp((HttpServletRequest) clientSub.asyncContext.getRequest()),
                                "polling", clientSub.clientMd5Map.size(), clientSub.probeRequestSize, groupKey);
                        clientSub.sendResponse(Collections.singletonList(groupKey));
                    }
                }
                
            } catch (Throwable t) {
                LogUtil.DEFAULT_LOG.error("data change error: {}", ExceptionUtil.getStackTrace(t));
            }
        }
        
        DataChangeTask(String groupKey) {
            this.groupKey = groupKey;
        }
        
        final String groupKey;
        
        final long changeTime = System.currentTimeMillis();
        
    }
    
    class StatTask implements Runnable {
        
        @Override
        public void run() {
            MEMORY_LOG.info("[long-pulling] client count " + allSubs.size());
            MetricsMonitor.getLongPollingMonitor().set(allSubs.size());
        }
    }
    
    public class ClientLongPolling implements Runnable {
        
        @Override
        public void run() {
            asyncTimeoutFuture = ConfigExecutor.scheduleLongPolling(() -> {
                try {
                    getRetainIps().put(ClientLongPolling.this.ip, System.currentTimeMillis());
                    
                    // Delete subscriber's relations.
                    boolean removeFlag = allSubs.remove(ClientLongPolling.this);
                    
                    if (removeFlag) {
                        
                        LogUtil.CLIENT_LOG.info("{}|{}|{}|{}|{}|{}", (System.currentTimeMillis() - createTime),
                                "timeout", RequestUtil.getRemoteIp((HttpServletRequest) asyncContext.getRequest()),
                                "polling", clientMd5Map.size(), probeRequestSize);
                        sendResponse(null);
                        
                    } else {
                        LogUtil.DEFAULT_LOG.warn("client subsciber's relations delete fail.");
                    }
                } catch (Throwable t) {
                    LogUtil.DEFAULT_LOG.error("long polling error:" + t.getMessage(), t.getCause());
                }
                
            }, timeoutTime, TimeUnit.MILLISECONDS);
            
            allSubs.add(this);
        }
        
        void sendResponse(List<String> changedGroups) {
            
            // Cancel time out task.
            if (null != asyncTimeoutFuture) {
                asyncTimeoutFuture.cancel(false);
            }
            generateResponse(changedGroups);
        }
        
        void generateResponse(List<String> changedGroups) {
            
            if (null == changedGroups) {
                // Tell web container to send http response.
                asyncContext.complete();
                return;
            }
            
            HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
            
            try {
                final String respString = MD5Util.compareMd5ResultString(changedGroups);
                
                // Disable cache.
                response.setHeader("Pragma", "no-cache");
                response.setDateHeader("Expires", 0);
                response.setHeader("Cache-Control", "no-cache,no-store");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().println(respString);
                asyncContext.complete();
            } catch (Exception ex) {
                PULL_LOG.error(ex.toString(), ex);
                asyncContext.complete();
            }
        }
        
        ClientLongPolling(AsyncContext ac, Map<String, String> clientMd5Map, String ip, int probeRequestSize,
                long timeoutTime, String appName, String tag) {
            this.asyncContext = ac;
            this.clientMd5Map = clientMd5Map;
            this.probeRequestSize = probeRequestSize;
            this.createTime = System.currentTimeMillis();
            this.ip = ip;
            this.timeoutTime = timeoutTime;
            this.appName = appName;
            this.tag = tag;
        }
        
        final AsyncContext asyncContext;
        
        final Map<String, String> clientMd5Map;
        
        final long createTime;
        
        final String ip;
        
        final String appName;
        
        final String tag;
        
        final int probeRequestSize;
        
        final long timeoutTime;
        
        Future<?> asyncTimeoutFuture;
        
        @Override
        public String toString() {
            return "ClientLongPolling{" + "clientMd5Map=" + clientMd5Map + ", createTime=" + createTime + ", ip='" + ip
                    + '\'' + ", appName='" + appName + '\'' + ", tag='" + tag + '\'' + ", probeRequestSize="
                    + probeRequestSize + ", timeoutTime=" + timeoutTime + '}';
        }
    }
    
    void generateResponse(HttpServletRequest request, HttpServletResponse response, List<String> changedGroups) {
        if (null == changedGroups) {
            return;
        }
        
        try {
            final String respString = MD5Util.compareMd5ResultString(changedGroups);
            // Disable cache.
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
            response.setHeader("Cache-Control", "no-cache,no-store");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println(respString);
        } catch (Exception ex) {
            PULL_LOG.error(ex.toString(), ex);
        }
    }
    
    void generate503Response(AsyncContext asyncContext, HttpServletResponse response, String message) {
        
        try {
            
            // Disable cache.
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
            response.setHeader("Cache-Control", "no-cache,no-store");
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.getWriter().println(message);
            asyncContext.complete();
        } catch (Exception ex) {
            PULL_LOG.error(ex.toString(), ex);
        }
    }
    
    public Map<String, Long> getRetainIps() {
        return retainIps;
    }
    
    public int getSubscriberCount() {
        return allSubs.size();
    }
}

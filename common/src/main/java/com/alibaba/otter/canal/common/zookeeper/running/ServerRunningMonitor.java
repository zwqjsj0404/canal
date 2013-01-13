package com.alibaba.otter.canal.common.zookeeper.running;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.exception.ZkException;
import org.I0Itec.zkclient.exception.ZkInterruptedException;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.I0Itec.zkclient.exception.ZkNodeExistsException;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.alibaba.otter.canal.common.AbstractCanalLifeCycle;
import com.alibaba.otter.canal.common.utils.BooleanMutex;
import com.alibaba.otter.canal.common.utils.JsonUtils;
import com.alibaba.otter.canal.common.zookeeper.ZkClientx;
import com.alibaba.otter.canal.common.zookeeper.ZookeeperPathUtils;

/**
 * 针对server的running节点控制
 * 
 * @author jianghang 2012-11-22 下午02:59:42
 * @version 4.1.3
 */
public class ServerRunningMonitor extends AbstractCanalLifeCycle {

    private static final Logger        logger       = LoggerFactory.getLogger(ServerRunningMonitor.class);
    private ZkClientx                  zkClient;
    private String                     destination;
    private IZkDataListener            dataListener;
    private BooleanMutex               mutex        = new BooleanMutex(false);
    private volatile boolean           release      = false;
    // 当前服务节点状态信息
    private ServerRunningData          serverData;
    // 当前实际运行的节点状态信息
    private volatile ServerRunningData activeData;
    private ScheduledExecutorService   delayExector = Executors.newScheduledThreadPool(1);
    private int                        delayTime    = 5;
    private ServerRunningListener      listener;

    public ServerRunningMonitor(ServerRunningData serverData){
        this();
        this.serverData = serverData;
    }

    public ServerRunningMonitor(){
        // 创建父节点
        dataListener = new IZkDataListener() {

            public void handleDataChange(String dataPath, Object data) throws Exception {
                MDC.put("destination", destination);
                ServerRunningData runningData = JsonUtils.unmarshalFromByte((byte[]) data, ServerRunningData.class);
                if (!isMine(runningData.getCid())) {
                    mutex.set(false);
                }

                if (!runningData.isActive() && isMine(runningData.getCid())) { // 说明出现了主动释放的操作，并且本机之前是active
                    release = true;
                    releaseRunning();// 彻底释放mainstem
                }

                activeData = (ServerRunningData) runningData;
            }

            public void handleDataDeleted(String dataPath) throws Exception {
                MDC.put("destination", destination);
                mutex.set(false);
                if (!release && isMine(activeData.getCid())) {
                    // 如果上一次active的状态就是本机，则即时触发一下active抢占
                    initRunning();
                } else {
                    // 否则就是等待delayTime，避免因网络瞬端或者zk异常，导致出现频繁的切换操作
                    delayExector.schedule(new Runnable() {

                        public void run() {
                            initRunning();
                        }
                    }, delayTime, TimeUnit.SECONDS);
                }
            }

        };

    }

    public void start() {
        super.start();

        String path = ZookeeperPathUtils.getDestinationServerRunning(destination);
        zkClient.subscribeDataChanges(path, dataListener);
        initRunning();
    }

    public void stop() {
        super.stop();

        String path = ZookeeperPathUtils.getDestinationServerRunning(destination);
        zkClient.unsubscribeDataChanges(path, dataListener);
        releaseRunning(); // 尝试一下release

    }

    public void initRunning() {
        if (!isStart()) {
            return;
        }

        String path = ZookeeperPathUtils.getDestinationServerRunning(destination);
        // 序列化
        byte[] bytes = JsonUtils.marshalToByte(serverData);
        try {
            mutex.set(false);
            zkClient.create(path, bytes, CreateMode.EPHEMERAL);
            activeData = serverData;
            processActiveEnter();// 触发一下事件
            mutex.set(true);
        } catch (ZkNodeExistsException e) {
            bytes = zkClient.readData(path, true);
            if (bytes == null) {// 如果不存在节点，立即尝试一次
                initRunning();
            } else {
                activeData = JsonUtils.unmarshalFromByte(bytes, ServerRunningData.class);
            }
        }
    }

    /**
     * 阻塞等待自己成为active，如果自己成为active，立马返回
     * 
     * @throws InterruptedException
     */
    public void waitForActive() throws InterruptedException {
        initRunning();
        mutex.get();
    }

    /**
     * 检查当前的状态
     */
    public boolean check() {
        String path = ZookeeperPathUtils.getDestinationServerRunning(destination);
        try {
            byte[] bytes = zkClient.readData(path);
            ServerRunningData eventData = JsonUtils.unmarshalFromByte(bytes, ServerRunningData.class);
            activeData = eventData;// 更新下为最新值
            // 检查下nid是否为自己
            boolean result = isMine(activeData.getCid());
            if (!result) {
                logger.warn("canal is running in node[{}] , but not in node[{}]", activeData.getCid(),
                            serverData.getCid());
            }
            return result;
        } catch (ZkNoNodeException e) {
            logger.warn("canal is not run any in node");
            return false;
        } catch (ZkInterruptedException e) {
            logger.warn("canal check is interrupt");
            Thread.interrupted();// 清除interrupt标记
            return check();
        } catch (ZkException e) {
            logger.warn("canal check is failed");
            return false;
        }
    }

    public boolean releaseRunning() {
        if (check()) {
            String path = ZookeeperPathUtils.getDestinationServerRunning(destination);
            zkClient.delete(path);
            mutex.set(false);
            processActiveExit();
            return true;
        }

        return false;
    }

    // ====================== helper method ======================

    private boolean isMine(Long targetNid) {
        return targetNid.equals(serverData.getCid());
    }

    private void processActiveEnter() {
        if (listener != null) {
            try {
                listener.processActiveEnter();
            } catch (Exception e) {
                logger.error("processSwitchActive failed", e);
            }
        }
    }

    private void processActiveExit() {
        if (listener != null) {
            try {
                listener.processActiveExit();
            } catch (Exception e) {
                logger.error("processSwitchActive failed", e);
            }
        }
    }

    public void setListener(ServerRunningListener listener) {
        this.listener = listener;
    }

    // ===================== setter / getter =======================

    public void setDelayTime(int delayTime) {
        this.delayTime = delayTime;
    }

    public void setServerData(ServerRunningData serverData) {
        this.serverData = serverData;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public void setZkClient(ZkClientx zkClient) {
        this.zkClient = zkClient;
    }

}
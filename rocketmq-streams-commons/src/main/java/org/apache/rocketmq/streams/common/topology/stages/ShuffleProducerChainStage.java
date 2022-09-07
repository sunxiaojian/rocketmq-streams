/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.streams.common.topology.stages;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.rocketmq.streams.common.channel.sink.AbstractSink;
import org.apache.rocketmq.streams.common.channel.sink.AbstractSupportShuffleSink;
import org.apache.rocketmq.streams.common.channel.sinkcache.impl.AbstractMultiSplitMessageCache;
import org.apache.rocketmq.streams.common.channel.source.ISource;
import org.apache.rocketmq.streams.common.channel.split.ISplit;
import org.apache.rocketmq.streams.common.component.ComponentCreator;
import org.apache.rocketmq.streams.common.configurable.IConfigurableService;
import org.apache.rocketmq.streams.common.context.AbstractContext;
import org.apache.rocketmq.streams.common.context.Context;
import org.apache.rocketmq.streams.common.context.IMessage;
import org.apache.rocketmq.streams.common.context.Message;
import org.apache.rocketmq.streams.common.topology.ChainPipeline;
import org.apache.rocketmq.streams.common.topology.model.AbstractRule;
import org.apache.rocketmq.streams.common.topology.model.AbstractStage;
import org.apache.rocketmq.streams.common.topology.model.IWindow;
import org.apache.rocketmq.streams.common.topology.shuffle.ShuffleMQCreator;
import org.apache.rocketmq.streams.common.utils.CompressUtil;
import org.apache.rocketmq.streams.common.utils.StringUtil;
import org.apache.rocketmq.streams.common.utils.TraceUtil;

public class ShuffleProducerChainStage<T extends IMessage, R extends AbstractRule> extends OutputChainStage<T>  {
    private static final Log LOG = LogFactory.getLog(ShuffleProducerChainStage.class);
    public static final String IS_COMPRESSION_MSG = "_is_compress_msg";
    public static final String COMPRESSION_MSG_DATA = "_compress_msg";
    public static final String MSG_FROM_SOURCE = "msg_from_source";
    public static final String ORIGIN_OFFSET = "origin_offset";

    public static final String ORIGIN_QUEUE_ID = "origin_queue_id";

    public static final String ORIGIN_QUEUE_IS_LONG = "origin_offset_is_LONG";

    public static final String ORIGIN_MESSAGE_HEADER = "origin_message_header";

    public static final String ORIGIN_SOURCE_NAME = "origin_offset_name";

    public static final String SHUFFLE_KEY = "SHUFFLE_KEY";

    public static final String ORIGIN_MESSAGE_TRACE_ID = "origin_request_id";

    protected static final String SHUFFLE_QUEUE_ID = "SHUFFLE_QUEUE_ID";
    protected static final String SHUFFLE_MESSAGES = "SHUFFLE_MESSAGES";
    /**
     * 消息所属的window
     */
    protected transient String MSG_OWNER = "MSG_OWNER";

    private static final String SHUFFLE_TRACE_ID = "SHUFFLE_TRACE_ID";

    private transient ShuffleMQCreator shuffleMQCreator;



    protected String shuffleOwnerName;//shuffle 拥有者到名子，如是窗口，则是windowname+groupname+updateflag
    protected String windowName;//Provide objects generated by shuffle key, such as window objects
    protected int splitCount;


    protected transient IWindow window;//generator shuffle key
    /**
     * used for shuffle
     */
    protected transient AbstractSupportShuffleSink producer;//sink for shuffle

    protected transient ShuffleMsgCache shuffleMsgCache = new ShuffleMsgCache();


    protected transient boolean isWindowTest = false;//used for test, can fired quickly
    protected transient AtomicLong COUNT = new AtomicLong(0);//usded for test

    /**
     * Layer 2 cache，Each piece is divided into multiple pieces
     */
    protected class ShuffleMsgCache extends AbstractMultiSplitMessageCache<Pair<ISplit, JSONObject>> {

        public ShuffleMsgCache() {
            super(messages -> {
                if (messages == null || messages.size() == 0) {
                    return true;
                }
                ISplit split = messages.get(0).getLeft();
                JSONObject jsonObject = messages.get(0).getRight();
                JSONArray allMsgs = jsonObject.getJSONArray(SHUFFLE_MESSAGES);
                for (int i = 1; i < messages.size(); i++) {
                    Pair<ISplit, JSONObject> pair = messages.get(i);
                    JSONObject msg = pair.getRight();
                    JSONArray jsonArray = msg.getJSONArray(SHUFFLE_MESSAGES);
                    if (jsonArray != null) {
                        allMsgs.addAll(jsonArray);
                    }
                }
                JSONObject zipJsonObject = new JSONObject();
                zipJsonObject.put(COMPRESSION_MSG_DATA, CompressUtil.gZip(jsonObject.toJSONString()));
                zipJsonObject.put(IS_COMPRESSION_MSG, true);
                producer.batchAdd(new Message(zipJsonObject), split);
                producer.flush(split.getQueueId());

                return true;
            });
        }

        @Override
        protected String createSplitId(Pair<ISplit, JSONObject> msg) {
            return msg.getLeft().getQueueId();
        }
    }

    @Override protected boolean initConfigurable() {
        this.sink=new AbstractSink() {
            @Override protected boolean batchInsert(List<IMessage> messages) {
                addMsg2ShuffleCache(messages);
                return true;
            }
        };
        this.sink.init();


        isWindowTest = ComponentCreator.getPropertyBooleanValue("window.fire.isTest");
        return super.initConfigurable();
    }
    protected transient AtomicBoolean hasNotify=new AtomicBoolean(false);
    @Override protected IMessage doSink(IMessage message, AbstractContext context) {
        if(hasNotify.compareAndSet(false,true)){
            notifyShuffleConsumerStart();
        }
        return super.doSink(message, context);
    }

    /**
     * When the producer receives the first piece of data, it will notify the consumer to start consumption
     */
    protected void notifyShuffleConsumerStart() {
        AbstractStage consumerStage=(AbstractStage)((ChainPipeline)pipeline).getStageMap().get(nextStageLabels.get(0));
        IMessage message=new Message(new JSONObject());
        consumerStage.doMessage(message,new Context(message));
    }

    /**
     *
     * @param messageList
     * @return
     */
    protected boolean addMsg2ShuffleCache(List<IMessage> messageList) {
        if(window!=null){
            //call back window domessage
            for(IMessage message:messageList){
                window.doMessage(message,new Context(message));
            }
        }
        Map<Integer, JSONArray> shuffleMap = keyByMsg(messageList);
        if (shuffleMap != null && shuffleMap.size() > 0) {
            Set<String> splitIds = new HashSet<>();

            for (Map.Entry<Integer, JSONArray> entry : shuffleMap.entrySet()) {
                ISplit split = this.shuffleMQCreator.getQueueList().get(entry.getKey());
                JSONObject msg = createMsg(entry.getValue(), split);

                shuffleMsgCache.addCache(new MutablePair<>(split, msg));
                splitIds.add(split.getQueueId());

            }

        }
        if (isWindowTest) {
            long count = COUNT.addAndGet(messageList.size());
            System.out.println(shuffleOwnerName + " send shuffle msg count is " + count);
            shuffleMsgCache.flush();
        }
        return true;
    }



    /**
     * 对接收的消息按照不同shuffle key进行分组
     *
     * @param messages
     * @return
     */
    protected Map<Integer, JSONArray> keyByMsg(List<IMessage> messages) {
        Map<Integer, JSONArray> shuffleMap = new HashMap<>();
        for (IMessage msg : messages) {
            if (msg.getHeader().isSystemMessage()) {
                continue;
            }

            String shuffleKey = generateShuffleKey(msg);
            if (StringUtil.isEmpty(shuffleKey)) {
                shuffleKey = "<null>";
                LOG.debug("there is no group by value in message! " + msg.getMessageBody().toString());
                //continue;
            }
            Integer index = this.shuffleMQCreator.hash(shuffleKey);
            JSONObject body = msg.getMessageBody();
            String offset = msg.getHeader().getOffset();
            String queueId = msg.getHeader().getQueueId();

            body.put(ORIGIN_OFFSET, offset);
            body.put(ORIGIN_QUEUE_ID, queueId);
            body.put(ORIGIN_QUEUE_IS_LONG, msg.getHeader().getMessageOffset().isLongOfMainOffset());
            body.put(ORIGIN_MESSAGE_HEADER, JSONObject.toJSONString(msg.getHeader()));
            body.put(ORIGIN_MESSAGE_TRACE_ID, msg.getHeader().getTraceId());
            body.put(SHUFFLE_KEY, shuffleKey);


            JSONArray jsonArray = shuffleMap.get(index);
            if (jsonArray == null) {
                jsonArray = new JSONArray();
                shuffleMap.put(index, jsonArray);
            }
            jsonArray.add(body);

        }
        return shuffleMap;
    }

    /**
     * Combine multiple messages into one to reduce the pressure on the shuffle queue and improve throughput
     * @param messages
     * @param split
     * @return
     */
    protected JSONObject createMsg(JSONArray messages, ISplit split) {
        JSONObject msg = new JSONObject();
        //分片id
        msg.put(SHUFFLE_QUEUE_ID, split.getQueueId());
        //合并的消息
        msg.put(SHUFFLE_MESSAGES, messages);
        //消息owner
        msg.put(MSG_OWNER, shuffleOwnerName);
        //
        try {
            List<String> traceList = new ArrayList<>();
            List<String> groupByList = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                JSONObject object = messages.getJSONObject(i);
                groupByList.add(object.getString("SHUFFLE_KEY"));
                traceList.add(object.getString(ORIGIN_MESSAGE_TRACE_ID));
            }
            String traceInfo = StringUtils.join(traceList);
            String groupInfo = StringUtils.join(groupByList);
            msg.put(SHUFFLE_TRACE_ID, StringUtils.join(traceList));
            TraceUtil.debug(traceInfo, "origin message out", split.getQueueId(), groupInfo, getConfigureName());
        } catch (Exception e) {
            //do nothing
        }
        return msg;
    }



    /**
     * If it is a window shuffle, calculate the shuffle key through the window object; otherwise, give the shuffle key randomly according to the number of queues
     *
     * @param message
     * @return
     */
    protected String generateShuffleKey(IMessage message){
        if(window!=null){
           return window.generateShuffleKey(message);
        }else {
            return (Math.random()*this.shuffleMQCreator.getQueueList().size()+1)+"";
        }
    }

    protected AbstractSupportShuffleSink createProducer(String name, ISource source) {
        this.shuffleMQCreator=ShuffleMQCreator.createShuffleCreator(((ChainPipeline)this.getPipeline()).getSource(),getPipeline().getNameSpace(),getPipeline().getConfigureName(),shuffleOwnerName,splitCount);
        return shuffleMQCreator.getProducer();
    }



    @Override public boolean isAsyncNode() {
        return true;
    }

    @Override
    public void doProcessAfterRefreshConfigurable(IConfigurableService configurableService) {
        window=configurableService.queryConfigurable(windowName,IWindow.TYPE);
        if(this.producer==null){
            this.producer=createProducer(shuffleOwnerName,((ChainPipeline)this.getPipeline()).getSource());
        }
    }
    @Override
    protected boolean openMockChannel() {
        return false;
    }

    public String getShuffleOwnerName() {
        return shuffleOwnerName;
    }

    public void setShuffleOwnerName(String shuffleOwnerName) {
        this.shuffleOwnerName = shuffleOwnerName;
    }

    public String getWindowName() {
        return windowName;
    }

    public void setWindowName(String windowName) {
        this.windowName = windowName;
    }

    public int getSplitCount() {
        return splitCount;
    }

    public void setSplitCount(int splitCount) {
        this.splitCount = splitCount;
    }
}
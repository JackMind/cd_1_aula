package com.isel.cd.server;


import com.isel.cd.server.messages.*;
import rpcsclientstubs.Data;
import rpcsclientstubs.Key;
import spread.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class LeaderManager implements SpreadMessageListenerInterface {

    private final DatabaseRepository database;
    private final SpreadConnection connection;
    private final String groupId;
    private final SpreadGroup me;

    public LeaderManager(final SpreadConnection connection, final String groupId, final DatabaseRepository database) {
        this.connection = connection;
        this.me = connection.getPrivateGroup();
        this.groupId = groupId;
        this.database = database;
    }

    private SpreadGroup leader;
    private volatile boolean waitStartupDataUpdate = false;

    @Override
    public void notifyServerLeave(SpreadGroup group, MembershipInfo info) {
        if(group.equals(this.leader) ){
            System.out.println("Leader: " + this.leader + " no longer exists");

            int max = 0;
            int leaderIndex = 0;
            for(int index = 0; index < info.getMembers().length; index++){
                if(this.getServerName(info.getMembers()[index]).equals("Service")){
                    continue;
                }
                int cardinality = this.getServerNameCardinality(info.getMembers()[index]);
                if(cardinality > max){
                    max = cardinality;
                    leaderIndex = index;
                }
            }

            this.leader = info.getMembers()[leaderIndex];
            System.out.println("New leader: " + this.leader + " with cardinality: " + max);
        }

        if(amILeader()){
            System.out.println("I'm leader, notify everyone!");
            this.sendIAmLeaderMessage();
        }
    }

    @Override
    public void assignNewLeader(SpreadMessage spreadMessage) {
        if(!this.me.equals(spreadMessage.getSender())){
            leader = spreadMessage.getSender();
            System.out.println("NEW LEADER! " + leader);

            if(waitStartupDataUpdate){
                this.requestDataUpdate();
            }
        }
    }

    private void requestDataUpdate() {
        System.out.println("Request data update to leader: " + this.leader);
        AtomicBoolean workingOnUpdate = new AtomicBoolean(true);


        while (workingOnUpdate.get());

        waitStartupDataUpdate = false;
        System.out.println("Update data finished!");
    }

    @Override
    public void assignFirstLeader(SpreadGroup spreadGroup) {
        this.leader = spreadGroup;
        System.out.println("First leader: " + this.leader);
    }

    @Override
    public void requestWhoIsLeader() {
        System.out.println("Request who is leader!");
        try{
            connection.multicast(createMulticastMessage(new BaseMessage(BaseMessage.TYPE.WHO_IS_LEADER)));
        } catch (SpreadException exception){
            System.out.println("Error multicasting message: requestWhoIsLeader " + exception);
        }
    }

    @Override
    public void whoIsLeaderRequestedNotifyParticipantsWhoIsLeader() {
        if(this.amILeader()){
            System.out.println("Who is leader received, notify that i am leader! " + this.me);
            this.sendIAmLeaderMessage();
        }
    }

    @Override
    public boolean doIHaveLeader() {
        return !(this.leader == null);
    }

    @Override
    public void appendDataReceived(NewDataFromLeader appendData) {
        if(!this.amILeader()){
            System.out.println("Data update received from leader!");
            database.save(DataEntity.builder()
                    .key(appendData.getData().getKey())
                    .data(appendData.getData() == null ? new DataEntity.Data() : appendData.getData().getData() )
                    .build());

            WaitingDataWriteCallback waitingDataWriteCallback = waitingDataWritten.get(appendData.getData().getKey());
            if(waitingDataWriteCallback != null){
                System.out.println("Calling response callback...");
                waitingDataWriteCallback.dataWritten(true);
            }
        }
    }

    @Override
    public void dataRequestedToLeader(SpreadMessage spreadMessage) throws SpreadException {
        if(this.amILeader()){
            AskDataToLeader askDataToLeader = (AskDataToLeader) spreadMessage.getObject();
            System.out.println("Data requested to leader: " + askDataToLeader);
            Optional<DataEntity> data = this.database.findById(askDataToLeader.getKey());
            if(data.isPresent()){
                System.out.println("Data found: " + data.get());
                connection.multicast(createUnicastMessage(new ResponseDataFromLeader(data.get()), spreadMessage.getSender()));
            }else{
                System.out.println("Data not found");
                connection.multicast(createUnicastMessage(new ResponseDataFromLeader(askDataToLeader.getKey()), spreadMessage.getSender()));
            }
        }
    }

    @Override
    public void receivedResponseData(SpreadMessage spreadMessage) throws SpreadException {
        ResponseDataFromLeader data = (ResponseDataFromLeader) spreadMessage.getObject();
        System.out.println("Received data from leader: " + data);
        System.out.println("Calling response callback...");
        waitingData.get(data.getData().getKey()).dataReceived(data.getData());
    }

    @Override
    public void writeDataToLeader(SpreadMessage spreadMessage) throws SpreadException {
        if(this.amILeader()){
            WriteDataToLeader data = (WriteDataToLeader) spreadMessage.getObject();
            System.out.println("Received data to be written by leader: " + data);
            this.saveDataAndUpdateParticipants(new DataEntity(data.getDataEntity()));
        }
    }

    public boolean amILeader(){
        return this.leader != null && this.leader.equals(this.me);
    }

    public void sendAppendDataToParticipants(DataEntity data){
        if(this.amILeader()){
            System.out.println("Send append data to all participants! " + data);
            try{
                connection.multicast(createMulticastMessage(new NewDataFromLeader(data)));
            } catch (SpreadException spreadException){
                System.out.println(spreadException);
            }
        }
    }

    private String getServerName(SpreadGroup group){
        return group.toString().split("#")[1];
    }

    private int getServerNameCardinality(SpreadGroup group){
        String server = this.getServerName(group);
        return Integer.parseInt(server.substring(server.length()-1));
    }

    private SpreadMessage createMulticastMessage(BaseMessage message){
        SpreadMessage spreadMessage = new SpreadMessage();
        try{
            spreadMessage.setObject(message);
            spreadMessage.addGroup(groupId);
            spreadMessage.setReliable();
        }catch (SpreadException exception){
            System.out.println("Error creating message: " + exception);
        }
        return spreadMessage;
    }

    private SpreadMessage createUnicastMessage(BaseMessage message, SpreadGroup group){
        SpreadMessage spreadMessage = new SpreadMessage();
        try{
            spreadMessage.setObject(message);
            spreadMessage.addGroup(group.toString());
            spreadMessage.setReliable();
        }catch (SpreadException exception){
            System.out.println("Error creating message: " + exception);
        }
        return spreadMessage;
    }


    private void sendIAmLeaderMessage() {
        try{
            connection.multicast(createMulticastMessage(new NewLeader()));
        }catch (SpreadException exception){
            System.out.println("Error multicasting message: sendIAmLeaderMessage " + exception);
        }
    }

    private final Map<String, WaitingDataReadCallback> waitingData = new HashMap<>();

    public DataEntity requestDataToLeader(Key request) {
        AtomicReference<DataEntity> response = new AtomicReference<>();
        WaitingDataReadCallback waitingDataReadCallback = (dataEntity) -> {
            System.out.println("Response callback: " + dataEntity);
            response.set(dataEntity);
        };

        try{
            System.out.println("Request data to leader with key: " + request.getKey());
            connection.multicast(createUnicastMessage(new AskDataToLeader(request.getKey()), this.leader));
            waitingData.put(request.getKey(), waitingDataReadCallback);
        }catch (SpreadException exception){
            System.out.println("Error multicasting message: sendIAmLeaderMessage " + exception);
        }

        while (response.get()==null);
        //TODO: timeout

        waitingData.remove(request.getKey());
        System.out.println("Response received");

        return response.get();
    }

    private final Map<String, WaitingDataWriteCallback> waitingDataWritten = new HashMap<>();


    public boolean writeDataToLeader(Data request){
        AtomicReference<Boolean> response = new AtomicReference<>();
        WaitingDataWriteCallback waitingDataWriteCallback = successful -> {
            System.out.println("Response callback data written: " + successful);
            response.set(successful);
        };

        try{
            System.out.println("Write data to leader with key: " + request);
            connection.multicast(createUnicastMessage(
                    new WriteDataToLeader(new DataEntity(request.getKey(), new DataEntity.Data(request.getData()))),
                    this.leader));

            waitingDataWritten.put(request.getKey(), waitingDataWriteCallback);
        }catch (SpreadException exception){
            System.out.println("Error multicasting message: writeDataToLeader " + exception);
        }

        while (response.get()==null);
        //TODO: timeout

        waitingDataWritten.remove(request.getKey());
        System.out.println("Data written " + (response.get() ? "successfully" : "unsuccessfully") );

        return response.get();
    }

    public SpreadGroup getLeader() {
        return leader;
    }

    public void saveDataAndUpdateParticipants(DataEntity request) {
        this.database.save(request);
        System.out.println("Saved on leader db" + request);
        this.sendAppendDataToParticipants(request);
    }

    public interface WaitingDataReadCallback {
        void dataReceived(DataEntity dataEntity);
    }


    public interface WaitingDataWriteCallback {
        void dataWritten(boolean successful);
    }
}

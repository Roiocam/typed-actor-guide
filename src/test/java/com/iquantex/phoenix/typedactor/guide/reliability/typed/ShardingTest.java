package com.iquantex.phoenix.typedactor.guide.reliability.typed;

import com.iquantex.phoenix.typedactor.guide.reliability.mock.DB;
import com.iquantex.phoenix.typedactor.guide.reliability.protocol.TodoListMessage;
import com.iquantex.phoenix.typedactor.guide.reliability.protocol.TodoResponse;
import com.iquantex.phoenix.typedactor.guide.reliability.protocol.TodoServiceMessage;
import com.iquantex.phoenix.typedactor.guide.reliability.protocol.TodoServiceMessage.UpdateTodo;
import com.iquantex.phoenix.typedactor.guide.reliability.protocol.TodoState;
import com.iquantex.phoenix.typedactor.guide.reliability.typed.sharding.TodoListActor;
import com.iquantex.phoenix.typedactor.guide.reliability.typed.sharding.TodoService;

import com.typesafe.config.ConfigFactory;
import akka.Done;
import akka.actor.Address;
import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.typed.ActorRef;
import akka.actor.typed.delivery.ConsumerController;
import akka.actor.typed.delivery.ConsumerController.SequencedMessage;
import akka.actor.typed.javadsl.AskPattern;
import akka.cluster.sharding.typed.ShardingEnvelope;
import akka.cluster.sharding.typed.delivery.ShardingConsumerController;
import akka.cluster.sharding.typed.delivery.ShardingProducerController;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import akka.cluster.typed.Cluster;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** @author AndyChen */
@Slf4j
public class ShardingTest {

    private static ActorTestKit testKit;
    private static DB db;
    private static ActorRef<TodoServiceMessage> producer;

    @BeforeAll
    public static void setup() throws InterruptedException {
        Map<String, Object> map = new HashMap();
        map.put("akka.cluster.seed-nodes", Arrays.asList("akka://ShardingTest@127.0.0.1:2551"));
        testKit =
                ActorTestKit.create(
                        ConfigFactory.parseMap(map)
                                .withFallback(ConfigFactory.load("reference-cluster.conf")));
        db = theDatabaseImplementation();
        // ??????????????????
        EntityTypeKey<ConsumerController.SequencedMessage<TodoListMessage>> entityTypeKey =
                EntityTypeKey.create(ShardingConsumerController.entityTypeKeyClass(), "todo");
        ActorRef<ShardingEnvelope<SequencedMessage<TodoListMessage>>> region =
                ClusterSharding.get(testKit.system())
                        .init(
                                Entity.of(
                                        entityTypeKey,
                                        entityContext ->
                                                ShardingConsumerController.create(
                                                        start ->
                                                                TodoListActor.create(
                                                                        entityContext.getEntityId(),
                                                                        db,
                                                                        start))));
        // ????????? producer
        Address selfAddress = Cluster.get(testKit.system()).selfMember().address();
        String producerId = "todo-producer-" + selfAddress.hostPort();
        ActorRef<ShardingProducerController.Command<TodoListMessage>> producerController =
                testKit.spawn(
                        ShardingProducerController.create(
                                TodoListMessage.class, producerId, region, Optional.empty()),
                        "producerController");
        producer = testKit.spawn(TodoService.create(producerController), "producer");

        Thread.sleep(1000);
    }

    @Test
    public void test() throws InterruptedException {
        // ??????????????????????????? ????????????(monthly_goal)
        CompletionStage<TodoResponse> addTask =
                AskPattern.ask(
                        producer,
                        replyTo -> new UpdateTodo("monthly_goal", "learning java", false, replyTo),
                        Duration.ofSeconds(1),
                        testKit.scheduler());
        CompletionStage<TodoResponse> addTask2 =
                AskPattern.ask(
                        producer,
                        replyTo ->
                                new UpdateTodo("monthly_goal", "learning python", false, replyTo),
                        Duration.ofSeconds(1),
                        testKit.scheduler());

        TodoResponse join = addTask.toCompletableFuture().join();
        TodoResponse join2 = addTask2.toCompletableFuture().join();
        log.info("?????? TodoList[monthly_goal] ??????={}", join);
        log.info("?????? TodoList[monthly_goal] ??????={}", join2);
        Assertions.assertEquals(TodoResponse.ACCEPTED, join);
        Assertions.assertEquals(TodoResponse.ACCEPTED, join2);
        // ??????????????????????????? ????????????(annual_goal)
        CompletionStage<TodoResponse> addTask3 =
                AskPattern.ask(
                        producer,
                        replyTo -> new UpdateTodo("annual_goal", "learning c#", false, replyTo),
                        Duration.ofSeconds(1),
                        testKit.scheduler());
        TodoResponse join3 = addTask3.toCompletableFuture().join();
        log.info("?????? TodoList[annual_goal] ??????={}", join3);
        Assertions.assertEquals(TodoResponse.ACCEPTED, join3);
        // ?????? List ?????????????????????
        CompletionStage<TodoState> load = db.load("monthly_goal");
        TodoState monthlyState = load.toCompletableFuture().join();
        log.info("?????? monthlyState ??????={}", JSON.toJSONString(monthlyState));
        CompletionStage<TodoState> load2 = db.load("annual_goal");
        TodoState annualGoal = load2.toCompletableFuture().join();
        log.info("?????? annualGoal ??????={}", JSON.toJSONString(annualGoal));
        // ??????
        Assertions.assertEquals(
                Arrays.asList("learning java", "learning python"), monthlyState.getTasks());
        Assertions.assertEquals(Arrays.asList("learning c#"), annualGoal.getTasks());

        Thread.sleep(1000);
        // ??????????????????
        CompletionStage<TodoResponse> addTask4 =
                AskPattern.ask(
                        producer,
                        replyTo -> new UpdateTodo("monthly_goal", "learning python", true, replyTo),
                        Duration.ofSeconds(1),
                        testKit.scheduler());
        TodoResponse join4 = addTask4.toCompletableFuture().join();
        log.info("?????? TodoList[monthly_goal] ??????={}", join4);
        Assertions.assertEquals(TodoResponse.ACCEPTED, join4);
        // ????????????????????????????????????????????? Actor
        CompletionStage<TodoState> load3 = db.load("monthly_goal");
        TodoState monthlyState2 = load3.toCompletableFuture().join();
        log.info("?????? monthlyState ??????={}", JSON.toJSONString(monthlyState2));
        CompletionStage<TodoState> load4 = db.load("annual_goal");
        TodoState annualGoal2 = load4.toCompletableFuture().join();
        log.info("?????? annualGoal ??????={}", JSON.toJSONString(annualGoal2));
        // ??????
        Assertions.assertEquals(Arrays.asList("learning java"), monthlyState2.getTasks());
        Assertions.assertEquals(Arrays.asList("learning c#"), annualGoal2.getTasks());
        // ??????????????????
        log.info("?????????????????????????????????");
        CompletionStage<TodoResponse> addTask5 =
                AskPattern.ask(
                        producer,
                        replyTo -> new UpdateTodo("monthly_goal", "learning 1", false, replyTo),
                        Duration.ofSeconds(1),
                        testKit.scheduler());
        CompletionStage<TodoResponse> addTask6 =
                AskPattern.ask(
                        producer,
                        replyTo -> new UpdateTodo("monthly_goal", "learning 2", false, replyTo),
                        Duration.ofSeconds(1),
                        testKit.scheduler());
        CompletionStage<TodoResponse> addTask7 =
                AskPattern.ask(
                        producer,
                        replyTo -> new UpdateTodo("monthly_goal", "learning 3", false, replyTo),
                        Duration.ofSeconds(1),
                        testKit.scheduler());
        Assertions.assertEquals(TodoResponse.ACCEPTED, addTask5.toCompletableFuture().join());
        Assertions.assertEquals(TodoResponse.ACCEPTED, addTask6.toCompletableFuture().join());
        Assertions.assertEquals(TodoResponse.ACCEPTED, addTask7.toCompletableFuture().join());
        CompletionStage<TodoState> load5 = db.load("monthly_goal");
        TodoState monthlyState3 = load5.toCompletableFuture().join();
        log.info("?????? monthlyState ??????={}", JSON.toJSONString(monthlyState3));
    }

    private static DB theDatabaseImplementation() {
        return new DB() {
            private HashMap<String, TodoState> database = new HashMap<>();

            @Override
            public CompletionStage<Done> save(String id, TodoState state) {
                database.put(id, state);
                return CompletableFuture.completedFuture(Done.done());
            }

            @Override
            public CompletionStage<TodoState> load(String id) {
                return CompletableFuture.completedFuture(database.get(id));
            }
        };
    }
}

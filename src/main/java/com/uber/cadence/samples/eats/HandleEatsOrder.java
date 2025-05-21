/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.samples.eats;

import static com.uber.cadence.samples.common.SampleConstants.DOMAIN;

import com.uber.cadence.FeatureFlags;
import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.internal.compatibility.Thrift2ProtoAdapter;
import com.uber.cadence.internal.compatibility.proto.serviceclient.IGrpcServiceStubs;
import com.uber.cadence.serviceclient.ClientOptions;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.worker.WorkerFactory;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * PreWork 
 * setting up cadence locally or in a managed container environment 
 * locally just install docker using either brew or your package manager 
 * run the docker compose file in the cadence-samples-java/docker directory 
 * check the localhost:8080 to verify that the cadence web ui is running and the server under it is running 
 * 
 * managed container service 
 * run the docker compose file and make sure your individual containers are all in the same docker network 
 * verify the inter container access is working 
 * verify that there is no port conflict with the host machine and other services
 * or containers running on the host machine verify that the service is running and the web ui is
 * accessible as described above install the cadence cli in either a separate container or the host
 * machine, this might include installing extra dependencies
 *
 * <p>Local machine setup 
 * clone this repo, but you are ahead of that 
 * install maven, java, and docker
 * downgrade your java version to 11 or similar, the gradle version here does not support newer java versions 
 * get the cadence client libraries from maven using your ide and import them into your project 
 * additional manual steps might be required to add your dependencies properly 
 * build the project with gradle this will test all individual modules 
 * if everything is allright you can execute individual samples 
 * follow the instructions on the cadence website to run the samples 
 * 
 * I had some issues executing new samples, this command worked for me gradle clean run
 * -Pmain=com.uber.cadence.samples.eats.HandleEatsOrder 
 * The issues are related to spring and some specifics of the gradle build and tasks.
 *
 * <p>Notes
 * The cadence documentation has a bunch of useful tutorial that are out of date,
 * specifically using deprecated features for example this one
 * https://www.youtube.com/watch?v=5mBLspVKOAI 
 * refer exclusively to the samples in the github repo for actual development. 
 * This is an area where the documentation could be improved
 *
 * <p>Main Cadence concepts and prerequisites 
 * Cadence server must be running 
 * Cadence domain must be registered 
 * I am using the default domain, but you can create your own 
 * The cadence client code needs to be built using ./gradle build 
 * then you can execute the sample using the grade execute command above
 *
 * <p>Key concepts that are easy to overlook 
 * Cadence worker must be registered to the domain 
 * Cadence workflow must be registered to the worker 
 * Cadence activity must be registered to the worker
 * Cadence task list must be created 
 * Cadence workflow must be started in async mode if you are using signals 
 * Every interface needs to be implemented separately and registered to the worker so that it can be executed
 *
 * <p>Main workflow: 
 * Takes the order 
 * Waits for restaurant acknowledgment 
 * Gets restaurant response(adding latency to simulate the restaurant processing the order) 
 * Creates a child workflow to handle the delivery
 *
 * <p>Child workflow (Delivery): Simulates delivery time (5 seconds) Returns delivery status
 * 
 * Final tips on how to create a new workflow
 * start with a simple workflow that just prints and test that all the components are working are you grok the following concepts
 *     worker, workflow, activity relations
 *     how to start, stop and investigate workflows on the the web ui
 *     how to print and log messages as well as returning values
 *     how to use signals to control the workflow
 *     how async workflows work and how to avoid timeouts
 * add any extra functionality like children workflows, signals or async workflows and contesting timeouts.
 */

public class HandleEatsOrder {

  static final String TASK_LIST = "EatsOrderTaskList";
  static final int RESTAURANT_RESPONSE_LATENCY = 2;
  static final int DELIVERY_LATENCY = 5;

  // Cart is a simple POJO that contains the order id and the items in the cart
  public static class Cart {
    private final String orderId;
    private final List<String> content;

    public Cart(String orderId, List<String> content) {
      this.orderId = orderId;
      this.content = content;
    }

    public String getOrderId() {
      return orderId;
    }

    public List<String> getContent() {
      return content;
    }
  }

  /** Workflow interface has to have at least one method annotated with @WorkflowMethod. */
  public interface HandleEatsOrderWorkflow {
    // gets the order from the customer and starts the workflow and order processing
    /** @return order status string */
    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 30, taskList = TASK_LIST)
    String takeOrder(String userId, Cart cart, String restaurantId);

    @SignalMethod
    void waitForRestaurant(String name);

    // This is the signal method used for waiting on preparation of an order, the delay for
    // preparation is added here
    @SignalMethod
    void restaurantPrepare();
  }

  /** Activity interface is just a POJI. */
  public interface OrderActivities {
    @ActivityMethod(scheduleToCloseTimeoutSeconds = 2)
    String acknowledgeOrder(String userId, Cart cart, String restaurantId);

    @ActivityMethod(scheduleToCloseTimeoutSeconds = 2)
    String restaurantResponse(String userId, Cart cart, String restaurantId);
  }

  /** The child workflow interface. */
  // this is where the delivery delay is added
  public interface DeliveryWorkflow {
    @WorkflowMethod
    String deliverOrder(String userId, Cart cart, String restaurantId);
  }

  /** GreetingWorkflow implementation that calls GreetingsActivities#composeGreeting. */
  public static class HandleEatsOrderWorkflowImpl implements HandleEatsOrderWorkflow {

    /**
     * Activity stub implements activity interface and proxies calls to it to Cadence activity
     * invocations. Because activities are reentrant, only a single stub can be used for multiple
     * activity invocations.
     */
    private final OrderActivities activities = Workflow.newActivityStub(OrderActivities.class);

    // this is a boolean flag to check if the restaurant response has been received
    private boolean isRestaurantResponseReceived = false;

    @Override
    public String takeOrder(String userId, Cart cart, String restaurantId) {
      // Step 1: Acknowledge order
      String orderStatus = activities.acknowledgeOrder(userId, cart, restaurantId);

      // Step 2: Wait for restaurant preparation
      Workflow.await(() -> isRestaurantResponseReceived);
      String restaurantResponse = activities.restaurantResponse(userId, cart, restaurantId);

      // Step 3: Start delivery
      DeliveryWorkflow delivery = Workflow.newChildWorkflowStub(DeliveryWorkflow.class);
      String deliveryStatus = delivery.deliverOrder(userId, cart, restaurantId);

      return String.format("%s\n%s\n%s", orderStatus, restaurantResponse, deliveryStatus);
    }

    @Override
    public void waitForRestaurant(String name) {
      System.out.println("Waiting for " + name + " response...");
    }

    @Override
    public void restaurantPrepare() {
      Workflow.sleep(Duration.ofSeconds(RESTAURANT_RESPONSE_LATENCY));
      isRestaurantResponseReceived = true;
      System.out.println(
          "Restaurant received your order! Preparing order ETA: "
              + RESTAURANT_RESPONSE_LATENCY
              + " seconds");
    }
  }

  // Child Workflow Implementation
  public static class DeliveryWorkflowImpl implements DeliveryWorkflow {
    @Override
    public String deliverOrder(String userId, Cart cart, String restaurantId) {
      Workflow.sleep(Duration.ofSeconds(DELIVERY_LATENCY));
      return String.format(
          "Your order is in front of your door!\nUser: %s\nRestaurant: %s", userId, restaurantId);
    }
  }

  static class OrderActivitiesImpl implements OrderActivities {
    @Override
    public String acknowledgeOrder(String userId, Cart cart, String restaurantId) {
      String items = String.join(", ", cart.getContent());
      System.out.printf(
          "Order received!\nUser ID: %s\nOrder ID: %s\nItems: %s\nRestaurant: %s\n",
          userId, cart.getOrderId(), items, restaurantId);
      return "Order acknowledged";
    }

    @Override
    public String restaurantResponse(String userId, Cart cart, String restaurantId) {
      return String.format(
          "Order completed\nUser: %s\nOrder: %s\nRestaurant: %s",
          userId, cart.getOrderId(), restaurantId);
    }
  }

  public static void main(String[] args) {
    WorkflowClient workflowClient =
        WorkflowClient.newInstance(
            new Thrift2ProtoAdapter(
                IGrpcServiceStubs.newInstance(
                    ClientOptions.newBuilder()
                        .setFeatureFlags(
                            new FeatureFlags()
                                .setWorkflowExecutionAlreadyCompletedErrorEnabled(true))
                        .setPort(7833)
                        .build())),
            WorkflowClientOptions.newBuilder().setDomain(DOMAIN).build());
    // Get worker to poll the task list.
    WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
    Worker worker = factory.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(
        HandleEatsOrderWorkflowImpl.class, DeliveryWorkflowImpl.class);
    worker.registerActivitiesImplementations(new OrderActivitiesImpl());

    // start the worker execution
    factory.start();

    // generate random guid for the workflow
    String workflowId = UUID.randomUUID().toString();

    // Start a workflow execution. Usually this is done from another program.
    // Get a workflow stub using the same task list the worker uses.
    // The newly started workflow is going to have the workflowId generated above.
    WorkflowOptions workflowOptions =
        new WorkflowOptions.Builder()
            .setTaskList(TASK_LIST)
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(30))
            .setWorkflowId(workflowId)
            .build();
    HandleEatsOrderWorkflow workflow =
        workflowClient.newWorkflowStub(HandleEatsOrderWorkflow.class, workflowOptions);
    // Start workflow asynchronously
    Cart cart = new Cart("123", Arrays.asList("Pizza", "Soda"));
    String userId = UUID.randomUUID().toString().substring(0, 8);
    String restaurantId = UUID.randomUUID().toString().substring(0, 4);
    WorkflowClient.start(workflow::takeOrder, userId, cart, restaurantId);

    // Send the signal to unblock the workflow
    workflow.restaurantPrepare();

    // Now wait for the workflow to complete
    String result = workflow.takeOrder(userId, cart, restaurantId);
    System.out.println("Workflow result: " + result);
    System.exit(0);
  }
}

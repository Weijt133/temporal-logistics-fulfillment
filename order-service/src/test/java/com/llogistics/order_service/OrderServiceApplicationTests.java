package com.llogistics.order_service;

import com.llogistics.order_service.workflow.OrderWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
		properties = "spring.temporal.test-server.enabled=true"
)
class OrderServiceApplicationTests {

	@Autowired
	private WorkflowClient workflowClient;

	@Test
	@Timeout(20)
	void workflowCompletes() {
		String orderId = "test-" + UUID.randomUUID();

		OrderWorkflow workflow = workflowClient.newWorkflowStub(
				OrderWorkflow.class,
				WorkflowOptions.newBuilder()
						.setWorkflowId("order-" + orderId)
						.setTaskQueue("order-fulfillment")
						.build()
		);

		assertEquals(
				"Accepted demo order " + orderId,
				workflow.process(orderId)
		);
	}
}
package com.llogistics.order_service.workflow;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.client.WorkflowClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class WorkflowInspectionService {
    private final WorkflowClient client;

    public WorkflowInspectionService(WorkflowClient client) {
        this.client = client;
    }

    public WorkflowView inspect(String workflowId) {
        try {
            // One deadline bounds both description and paginated history reads.
            var stub = client.getWorkflowServiceStubs().blockingStub().withDeadlineAfter(4, TimeUnit.SECONDS);
            var description = stub.describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(client.getOptions().getNamespace())
                    .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId)).build());
            var info = description.getWorkflowExecutionInfo();
            var request = GetWorkflowExecutionHistoryRequest.newBuilder()
                    .setNamespace(client.getOptions().getNamespace())
                    .setExecution(info.getExecution()).setMaximumPageSize(1000);
            var activities = new LinkedHashMap<Long, ActivityView>();
            int pages = 0;
            do {
                var response = stub.getWorkflowExecutionHistory(request.build());
                for (var event : response.getHistory().getEventsList()) {
                    if (event.hasActivityTaskScheduledEventAttributes()) {
                        activities.put(event.getEventId(), new ActivityView(
                                event.getActivityTaskScheduledEventAttributes().getActivityType().getName(),
                                "SCHEDULED", 0));
                    } else if (event.hasActivityTaskStartedEventAttributes()) {
                        var started = event.getActivityTaskStartedEventAttributes();
                        activities.computeIfPresent(started.getScheduledEventId(), (id, old) ->
                                new ActivityView(old.name(), "RUNNING", Math.max(old.attempts(), started.getAttempt())));
                    } else if (event.hasActivityTaskCompletedEventAttributes()) {
                        complete(activities, event.getActivityTaskCompletedEventAttributes().getScheduledEventId(), "COMPLETED");
                    } else if (event.hasActivityTaskFailedEventAttributes()) {
                        complete(activities, event.getActivityTaskFailedEventAttributes().getScheduledEventId(), "FAILED");
                    } else if (event.hasActivityTaskTimedOutEventAttributes()) {
                        complete(activities, event.getActivityTaskTimedOutEventAttributes().getScheduledEventId(), "TIMED_OUT");
                    } else if (event.hasActivityTaskCanceledEventAttributes()) {
                        complete(activities, event.getActivityTaskCanceledEventAttributes().getScheduledEventId(), "CANCELED");
                    }
                }
                request.setNextPageToken(response.getNextPageToken());
                if (++pages >= 20 && !response.getNextPageToken().isEmpty()) {
                    return unavailable(workflowId, "Execution history is too large for this view. Open Temporal for details.");
                }
            } while (!request.getNextPageToken().isEmpty());

            // Temporal may omit intermediate retry events; pending Activity attempts are authoritative while running.
            for (var pending : description.getPendingActivitiesList()) {
                activities.replaceAll((id, activity) -> activity.name().equals(pending.getActivityType().getName())
                        && !List.of("COMPLETED", "FAILED", "TIMED_OUT", "CANCELED").contains(activity.status())
                        ? new ActivityView(activity.name(), pending.getState().name().replace("PENDING_ACTIVITY_STATE_", ""),
                                Math.max(activity.attempts(), pending.getAttempt())) : activity);
            }
            int shipmentAttempts = activities.values().stream().filter(a -> a.name().equals("CreateOrderShipment"))
                    .mapToInt(ActivityView::attempts).max().orElse(0);
            return new WorkflowView(workflowId, info.getExecution().getRunId(),
                    info.getStatus().name().replace("WORKFLOW_EXECUTION_STATUS_", ""), shipmentAttempts,
                    List.copyOf(activities.values()), null);
        } catch (StatusRuntimeException error) {
            if (error.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return new WorkflowView(workflowId, null, "NOT_FOUND", null, List.of(),
                        "The workflow has not started or its history is no longer retained.");
            }
            return unavailable(workflowId, "Temporal could not be reached. Execution details will refresh automatically.");
        }
    }

    private static void complete(LinkedHashMap<Long, ActivityView> activities, long id, String status) {
        activities.computeIfPresent(id, (key, old) -> new ActivityView(old.name(), status, old.attempts()));
    }

    private static WorkflowView unavailable(String id, String message) {
        return new WorkflowView(id, null, "UNAVAILABLE", null, List.of(), message);
    }

    public record ActivityView(String name, String status, int attempts) { }
    public record WorkflowView(String workflowId, String runId, String status, Integer shipmentAttempts,
                               List<ActivityView> activities, String message) { }
}

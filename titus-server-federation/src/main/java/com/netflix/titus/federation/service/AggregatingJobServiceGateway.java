/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.titus.federation.service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.protobuf.Empty;
import com.netflix.titus.api.federation.model.Cell;
import com.netflix.titus.api.model.callmetadata.CallMetadata;
import com.netflix.titus.api.service.TitusServiceException;
import com.netflix.titus.common.util.CollectionsExt;
import com.netflix.titus.common.util.ProtobufExt;
import com.netflix.titus.common.util.StringExt;
import com.netflix.titus.common.util.concurrency.CallbackCountDownLatch;
import com.netflix.titus.common.util.rx.EmitterWithMultipleSubscriptions;
import com.netflix.titus.common.util.rx.ReactorExt;
import com.netflix.titus.common.util.tuple.Pair;
import com.netflix.titus.federation.service.router.CellRouter;
import com.netflix.titus.federation.startup.GrpcConfiguration;
import com.netflix.titus.federation.startup.TitusFederationConfiguration;
import com.netflix.titus.grpc.protogen.Job;
import com.netflix.titus.grpc.protogen.JobAttributesDeleteRequest;
import com.netflix.titus.grpc.protogen.JobAttributesUpdate;
import com.netflix.titus.grpc.protogen.JobCapacityUpdate;
import com.netflix.titus.grpc.protogen.JobCapacityUpdateWithOptionalAttributes;
import com.netflix.titus.grpc.protogen.JobChangeNotification;
import com.netflix.titus.grpc.protogen.JobChangeNotification.JobUpdate;
import com.netflix.titus.grpc.protogen.JobChangeNotification.TaskUpdate;
import com.netflix.titus.grpc.protogen.JobDescriptor;
import com.netflix.titus.grpc.protogen.JobDisruptionBudgetUpdate;
import com.netflix.titus.grpc.protogen.JobId;
import com.netflix.titus.grpc.protogen.JobManagementServiceGrpc;
import com.netflix.titus.grpc.protogen.JobManagementServiceGrpc.JobManagementServiceStub;
import com.netflix.titus.grpc.protogen.JobProcessesUpdate;
import com.netflix.titus.grpc.protogen.JobQuery;
import com.netflix.titus.grpc.protogen.JobQueryResult;
import com.netflix.titus.grpc.protogen.JobStatusUpdate;
import com.netflix.titus.grpc.protogen.ObserveJobsQuery;
import com.netflix.titus.grpc.protogen.Pagination;
import com.netflix.titus.grpc.protogen.Task;
import com.netflix.titus.grpc.protogen.TaskAttributesDeleteRequest;
import com.netflix.titus.grpc.protogen.TaskAttributesUpdate;
import com.netflix.titus.grpc.protogen.TaskId;
import com.netflix.titus.grpc.protogen.TaskKillRequest;
import com.netflix.titus.grpc.protogen.TaskMoveRequest;
import com.netflix.titus.grpc.protogen.TaskQuery;
import com.netflix.titus.grpc.protogen.TaskQueryResult;
import com.netflix.titus.runtime.endpoint.common.grpc.GrpcUtil;
import com.netflix.titus.runtime.endpoint.metadata.CallMetadataResolver;
import com.netflix.titus.runtime.endpoint.metadata.V3HeaderInterceptor;
import com.netflix.titus.runtime.jobmanager.JobManagerCursors;
import com.netflix.titus.runtime.jobmanager.gateway.JobServiceGateway;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import rx.Completable;
import rx.Emitter;
import rx.Observable;

import static com.netflix.titus.api.jobmanager.JobAttributes.JOB_ATTRIBUTES_STACK;
import static com.netflix.titus.api.jobmanager.TaskAttributes.TASK_ATTRIBUTES_STACK;
import static com.netflix.titus.federation.service.CellConnectorUtil.callToCell;
import static com.netflix.titus.federation.service.PageAggregationUtil.combinePagination;
import static com.netflix.titus.federation.service.PageAggregationUtil.takeCombinedPage;
import static com.netflix.titus.runtime.endpoint.common.grpc.GrpcUtil.createRequestObservable;
import static com.netflix.titus.runtime.endpoint.common.grpc.GrpcUtil.createWrappedStub;
import static com.netflix.titus.runtime.endpoint.v3.grpc.GrpcJobQueryModelConverters.emptyGrpcPagination;

@Singleton
public class AggregatingJobServiceGateway implements JobServiceGateway {
    private static final Logger logger = LoggerFactory.getLogger(AggregatingJobServiceGateway.class);

    // some fields are required from each cell, so pagination cursors can be generated
    private static final Set<String> JOB_FEDERATION_MINIMUM_FIELD_SET = CollectionsExt.asSet("id", "status", "statusHistory");
    private static final Set<String> TASK_FEDERATION_MINIMUM_FIELD_SET = CollectionsExt.asSet("id", "status", "statusHistory");

    private final GrpcConfiguration grpcConfiguration;
    private final TitusFederationConfiguration federationConfiguration;
    private final CellConnector connector;
    private final AggregatingCellClient aggregatingClient;
    private AggregatingJobManagementServiceHelper jobManagementServiceHelper;
    private final CellRouter router;
    private final CallMetadataResolver callMetadataResolver;

    @Inject
    public AggregatingJobServiceGateway(GrpcConfiguration grpcConfiguration,
                                        TitusFederationConfiguration federationConfiguration,
                                        CellConnector connector,
                                        CellRouter router,
                                        CallMetadataResolver callMetadataResolver,
                                        AggregatingCellClient aggregatingClient,
                                        AggregatingJobManagementServiceHelper jobManagementServiceHelper) {

        this.grpcConfiguration = grpcConfiguration;
        this.federationConfiguration = federationConfiguration;
        this.connector = connector;
        this.router = router;
        this.callMetadataResolver = callMetadataResolver;
        this.aggregatingClient = aggregatingClient;
        this.jobManagementServiceHelper = jobManagementServiceHelper;
    }

    @Override
    public Observable<String> createJob(JobDescriptor jobDescriptor, CallMetadata callMetadata) {
        Cell cell = router.routeKey(jobDescriptor).orElse(null);
        if (cell == null) {
            // This should never happen in a correctly setup system.
            return Observable.error(new IllegalStateException("Internal system error. Routing rule not found"));
        }
        logger.debug("Routing JobDescriptor {} to Cell {}", jobDescriptor, cell);

        Optional<JobManagementServiceStub> optionalClient = CellConnectorUtil.toStub(cell, connector, JobManagementServiceGrpc::newStub);
        if (!optionalClient.isPresent()) {
            return Observable.error(TitusServiceException.cellNotFound(cell.getName()));
        }
        JobManagementServiceStub client = wrap(optionalClient.get());

        JobDescriptor withStackName = addStackName(jobDescriptor);
        return createRequestObservable(emitter -> {
            StreamObserver<JobId> streamObserver = GrpcUtil.createClientResponseObserver(
                    emitter,
                    jobId -> emitter.onNext(jobId.getId()),
                    emitter::onError,
                    emitter::onCompleted
            );
            wrap(Optional.ofNullable(callMetadata), client).createJob(withStackName, streamObserver);
        }, grpcConfiguration.getRequestTimeoutMs());
    }

    @Override
    public Completable updateJobCapacity(JobCapacityUpdate request) {
        Optional<CallMetadata> context = callMetadataResolver.resolve();

        Observable<Empty> result = jobManagementServiceHelper.findJobInAllCells(request.getJobId())
                .flatMap(response -> singleCellCall(response.getCell(),
                        (client, streamObserver) -> wrap(context, client).updateJobCapacity(request, streamObserver))
                );
        return result.toCompletable();
    }

    @Override
    public Completable updateJobCapacityWithOptionalAttributes(JobCapacityUpdateWithOptionalAttributes request) {
        Optional<CallMetadata> context = callMetadataResolver.resolve();
        Observable<Empty> result = jobManagementServiceHelper.findJobInAllCells(request.getJobId())
                .flatMap(response -> singleCellCall(response.getCell(),
                        (client, streamObserver) -> wrap(context, client).updateJobCapacityWithOptionalAttributes(request, streamObserver))
                );
        return result.toCompletable();
    }

    @Override
    public Completable updateJobProcesses(JobProcessesUpdate request) {
        Optional<CallMetadata> context = callMetadataResolver.resolve();

        Observable<Empty> result = jobManagementServiceHelper.findJobInAllCells(request.getJobId())
                .flatMap(response -> singleCellCall(response.getCell(),
                        (client, streamObserver) -> wrap(context, client).updateJobProcesses(request, streamObserver))
                );
        return result.toCompletable();
    }

    @Override
    public Completable updateJobStatus(JobStatusUpdate request) {
        Optional<CallMetadata> context = callMetadataResolver.resolve();

        Observable<Empty> result = jobManagementServiceHelper.findJobInAllCells(request.getId())
                .flatMap(response -> singleCellCall(response.getCell(),
                        (client, streamObserver) -> wrap(context, client).updateJobStatus(request, streamObserver))
                );
        return result.toCompletable();
    }

    @Override
    public Mono<Void> updateJobDisruptionBudget(JobDisruptionBudgetUpdate request) {
        Optional<CallMetadata> context = callMetadataResolver.resolve();

        Mono<Empty> result = jobManagementServiceHelper.findJobInAllCellsReact(request.getJobId())
                .flatMap(response -> singleCellCallReact(response.getCell(),
                        (client, streamObserver) -> wrap(context, client).updateJobDisruptionBudget(request, streamObserver))
                );
        return result.ignoreElement().cast(Void.class);
    }

    @Override
    public Mono<Void> updateJobAttributes(JobAttributesUpdate request) {
        Optional<CallMetadata> context = callMetadataResolver.resolve();

        Mono<Empty> result = jobManagementServiceHelper.findJobInAllCellsReact(request.getJobId())
                .flatMap(response -> singleCellCallReact(response.getCell(),
                        (client, streamObserver) -> wrap(context, client).updateJobAttributes(request, streamObserver))
                );
        return result.ignoreElement().cast(Void.class);
    }

    @Override
    public Mono<Void> deleteJobAttributes(JobAttributesDeleteRequest request) {
        Optional<CallMetadata> context = callMetadataResolver.resolve();

        Mono<Empty> result = jobManagementServiceHelper.findJobInAllCellsReact(request.getJobId())
                .flatMap(response -> singleCellCallReact(response.getCell(),
                        (client, streamObserver) -> wrap(context, client).deleteJobAttributes(request, streamObserver))
                );
        return result.ignoreElement().cast(Void.class);
    }

    @Override
    public Observable<Job> findJob(String jobId) {
        return jobManagementServiceHelper.findJobInAllCells(jobId)
                .map(CellResponse::getResult)
                .map(this::addStackName);
    }

    @Override
    public Observable<JobQueryResult> findJobs(JobQuery request) {
        if (request.getPage().getPageSize() <= 0) {
            return Observable.just(JobQueryResult.newBuilder()
                    .setPagination(emptyGrpcPagination(request.getPage()))
                    .build());
        }

        Set<String> fieldsFilter = Collections.emptySet();
        if (request.getFieldsCount() > 0) {
            fieldsFilter = new HashSet<>(request.getFieldsList());
            fieldsFilter.addAll(JOB_MINIMUM_FIELD_SET);
            request = request.toBuilder()
                    .addAllFields(fieldsFilter)
                    .addAllFields(JOB_FEDERATION_MINIMUM_FIELD_SET)
                    .build();
        }

        if (StringExt.isNotEmpty(request.getPage().getCursor()) || request.getPage().getPageNumber() == 0) {
            return findJobsWithCursorPagination(request, fieldsFilter);
        }
        // TODO: page number pagination
        return Observable.error(TitusServiceException.invalidArgument("pageNumbers are not supported, please use cursors"));
    }

    private Observable<JobQueryResult> findJobsWithCursorPagination(JobQuery request, Set<String> fields) {
        return aggregatingClient.call(JobManagementServiceGrpc::newStub, findJobsInCell(request))
                .map(CellResponse::getResult)
                .map(this::addStackName)
                .reduce(this::combineJobResults)
                .map(combinedResults -> {
                    Pair<List<Job>, Pagination> combinedPage = takeCombinedPage(
                            request.getPage(),
                            combinedResults.getItemsList(),
                            combinedResults.getPagination(),
                            JobManagerCursors.jobCursorOrderComparator(),
                            JobManagerCursors::newCursorFrom
                    );

                    if (!CollectionsExt.isNullOrEmpty(fields)) {
                        combinedPage = combinedPage.mapLeft(jobs -> jobs.stream()
                                .map(job -> ProtobufExt.copy(job, fields))
                                .collect(Collectors.toList())
                        );
                    }

                    return JobQueryResult.newBuilder()
                            .addAllItems(combinedPage.getLeft())
                            .setPagination(combinedPage.getRight())
                            .build();
                });
    }

    private ClientCall<JobQueryResult> findJobsInCell(JobQuery request) {
        return (client, streamObserver) -> wrap(client).findJobs(request, streamObserver);
    }

    private JobQueryResult combineJobResults(JobQueryResult one, JobQueryResult other) {
        Pagination pagination = combinePagination(one.getPagination(), other.getPagination());
        return JobQueryResult.newBuilder()
                .setPagination(pagination)
                .addAllItems(one.getItemsList())
                .addAllItems(other.getItemsList())
                .build();
    }

    @Override
    public Observable<JobChangeNotification> observeJob(String jobId) {
        JobId request = JobId.newBuilder().setId(jobId).build();
        return jobManagementServiceHelper.findJobInAllCells(jobId)
                .flatMap(response -> singleCellCallWithNoDeadline(response.getCell(),
                        (client, streamObserver) -> client.observeJob(request, streamObserver))
                );
    }

    @Override
    public Observable<JobChangeNotification> observeJobs(ObserveJobsQuery query) {
        final Observable<JobChangeNotification> observable = createRequestObservable(delegate -> {
            Emitter<JobChangeNotification> emitter = new EmitterWithMultipleSubscriptions<>(delegate);
            Map<Cell, JobManagementServiceStub> clients = CellConnectorUtil.stubs(connector, JobManagementServiceGrpc::newStub);
            final CountDownLatch markersEmitted = new CallbackCountDownLatch(clients.size(),
                    () -> emitter.onNext(buildJobSnapshotEndMarker())
            );
            clients.forEach((cell, client) -> {
                StreamObserver<JobChangeNotification> streamObserver = new FilterOutFirstMarker(emitter, markersEmitted);
                wrapWithNoDeadline(client).observeJobs(query, streamObserver);
            });
        });
        return observable.map(this::addStackName);
    }

    @Override
    public Completable killJob(String jobId) {
        Optional<CallMetadata> context = callMetadataResolver.resolve();

        JobId id = JobId.newBuilder().setId(jobId).build();
        Observable<Empty> result = jobManagementServiceHelper.findJobInAllCells(jobId)
                .flatMap(response -> singleCellCall(response.getCell(),
                        (client, streamObserver) -> wrap(context, client).killJob(id, streamObserver))
                );
        return result.toCompletable();
    }

    @Override
    public Observable<Task> findTask(String taskId) {
        return findTaskInAllCells(taskId).map(CellResponse::getResult).map(this::addStackName);
    }

    private Observable<CellResponse<JobManagementServiceStub, Task>> findTaskInAllCells(String taskId) {
        return aggregatingClient.callExpectingErrors(JobManagementServiceGrpc::newStub, findTaskInCell(taskId))
                .reduce(ResponseMerger.singleValue())
                .flatMap(response -> response.getResult()
                        .map(v -> Observable.just(CellResponse.ofValue(response)))
                        .onErrorGet(Observable::error)
                );
    }

    private ClientCall<Task> findTaskInCell(String taskId) {
        TaskId id = TaskId.newBuilder().setId(taskId).build();
        return (client, streamObserver) -> wrap(client).findTask(id, streamObserver);
    }

    @Override
    public Observable<TaskQueryResult> findTasks(TaskQuery request) {
        if (request.getPage().getPageSize() <= 0) {
            return Observable.just(TaskQueryResult.newBuilder()
                    .setPagination(emptyGrpcPagination(request.getPage()))
                    .build());
        }

        Set<String> fieldsFilter = Collections.emptySet();
        if (request.getFieldsCount() > 0) {
            fieldsFilter = new HashSet<>(request.getFieldsList());
            fieldsFilter.addAll(TASK_MINIMUM_FIELD_SET);
            request = request.toBuilder()
                    .addAllFields(fieldsFilter)
                    .addAllFields(TASK_FEDERATION_MINIMUM_FIELD_SET)
                    .build();
        }

        if (StringExt.isNotEmpty(request.getPage().getCursor()) || request.getPage().getPageNumber() == 0) {
            return findTasksWithCursorPagination(request, fieldsFilter);
        }
        // TODO: page number pagination
        return Observable.error(TitusServiceException.invalidArgument("pageNumbers are not supported, please use cursors"));
    }

    private Observable<TaskQueryResult> findTasksWithCursorPagination(TaskQuery request, Set<String> fields) {
        return aggregatingClient.call(JobManagementServiceGrpc::newStub, findTasksInCell(request))
                .map(CellResponse::getResult)
                .map(this::addStackName)
                .reduce(this::combineTaskResults)
                .map(combinedResults -> {
                    Pair<List<Task>, Pagination> combinedPage = takeCombinedPage(
                            request.getPage(),
                            combinedResults.getItemsList(),
                            combinedResults.getPagination(),
                            JobManagerCursors.taskCursorOrderComparator(),
                            JobManagerCursors::newCursorFrom
                    );

                    if (!CollectionsExt.isNullOrEmpty(fields)) {
                        combinedPage = combinedPage.mapLeft(tasks -> tasks.stream()
                                .map(task -> ProtobufExt.copy(task, fields))
                                .collect(Collectors.toList())
                        );
                    }

                    return TaskQueryResult.newBuilder()
                            .addAllItems(combinedPage.getLeft())
                            .setPagination(combinedPage.getRight())
                            .build();
                });
    }

    private ClientCall<TaskQueryResult> findTasksInCell(TaskQuery request) {
        return (client, streamObserver) -> wrap(client).findTasks(request, streamObserver);
    }

    private TaskQueryResult combineTaskResults(TaskQueryResult one, TaskQueryResult other) {
        Pagination pagination = combinePagination(one.getPagination(), other.getPagination());
        return TaskQueryResult.newBuilder()
                .setPagination(pagination)
                .addAllItems(one.getItemsList())
                .addAllItems(other.getItemsList())
                .build();
    }

    @Override
    public Completable killTask(TaskKillRequest request) {
        Optional<CallMetadata> context = callMetadataResolver.resolve();

        Observable<Empty> result = findTaskInAllCells(request.getTaskId())
                .flatMap(response -> singleCellCall(response.getCell(),
                        (client, streamObserver) -> wrap(context, client).killTask(request, streamObserver))
                );
        return result.toCompletable();
    }

    @Override
    public Completable updateTaskAttributes(TaskAttributesUpdate attributesUpdate) {
        Observable<Empty> result = findTaskInAllCells(attributesUpdate.getTaskId())
                .flatMap(response -> singleCellCall(response.getCell(),
                        (client, streamObserver) -> client.updateTaskAttributes(attributesUpdate, streamObserver)));
        return result.toCompletable();
    }

    @Override
    public Completable deleteTaskAttributes(TaskAttributesDeleteRequest deleteRequest) {
        Observable<Empty> result = findTaskInAllCells(deleteRequest.getTaskId())
                .flatMap(response -> singleCellCall(response.getCell(),
                        (client, streamObserver) -> client.deleteTaskAttributes(deleteRequest, streamObserver)));
        return result.toCompletable();
    }

    @Override
    public Completable moveTask(TaskMoveRequest taskMoveRequest) {
        Observable<Empty> result = findTaskInAllCells(taskMoveRequest.getTaskId())
                .flatMap(response -> singleCellCall(response.getCell(),
                        (client, streamObserver) -> client.moveTask(taskMoveRequest, streamObserver)));
        return result.toCompletable();
    }

    private JobQueryResult addStackName(JobQueryResult result) {
        List<Job> withStackName = result.getItemsList().stream().map(this::addStackName).collect(Collectors.toList());
        return result.toBuilder().clearItems().addAllItems(withStackName).build();
    }

    private TaskQueryResult addStackName(TaskQueryResult result) {
        List<Task> withStackName = result.getItemsList().stream().map(this::addStackName).collect(Collectors.toList());
        return result.toBuilder().clearItems().addAllItems(withStackName).build();
    }

    private JobDescriptor addStackName(JobDescriptor jobDescriptor) {
        return jobDescriptor.toBuilder()
                .putAttributes(JOB_ATTRIBUTES_STACK, federationConfiguration.getStack())
                .build();
    }

    private Job addStackName(Job job) {
        JobDescriptor jobDescriptor = addStackName(job.getJobDescriptor());
        return job.toBuilder().setJobDescriptor(jobDescriptor).build();
    }

    private Task addStackName(Task task) {
        return task.toBuilder()
                .putTaskContext(TASK_ATTRIBUTES_STACK, federationConfiguration.getStack())
                .build();
    }

    private JobChangeNotification addStackName(JobChangeNotification notification) {
        switch (notification.getNotificationCase()) {
            case JOBUPDATE:
                Job job = addStackName(notification.getJobUpdate().getJob());
                JobUpdate jobUpdate = notification.getJobUpdate().toBuilder().setJob(job).build();
                return notification.toBuilder().setJobUpdate(jobUpdate).build();
            case TASKUPDATE:
                Task task = addStackName(notification.getTaskUpdate().getTask());
                TaskUpdate taskUpdate = notification.getTaskUpdate().toBuilder().setTask(task).build();
                return notification.toBuilder().setTaskUpdate(taskUpdate).build();
            default:
                return notification;
        }
    }

    private static JobChangeNotification buildJobSnapshotEndMarker() {
        final JobChangeNotification.SnapshotEnd marker = JobChangeNotification.SnapshotEnd.newBuilder().build();
        return JobChangeNotification.newBuilder().setSnapshotEnd(marker).build();
    }

    private JobManagementServiceStub wrap(Optional<CallMetadata> context, JobManagementServiceStub client) {
        return context.map(c -> V3HeaderInterceptor.attachCallMetadata(client, c)).orElse(client);
    }

    private JobManagementServiceStub wrap(JobManagementServiceStub client) {
        return createWrappedStub(client, callMetadataResolver, grpcConfiguration.getRequestTimeoutMs());
    }

    private JobManagementServiceStub wrapWithNoDeadline(JobManagementServiceStub client) {
        return createWrappedStub(client, callMetadataResolver);
    }

    private <T> Observable<T> singleCellCall(Cell cell, ClientCall<T> clientCall) {
        return callToCell(cell, connector, JobManagementServiceGrpc::newStub,
                (client, streamObserver) -> clientCall.accept(wrap(client), streamObserver));
    }

    private <T> Mono<T> singleCellCallReact(Cell cell, ClientCall<T> clientCall) {
        return ReactorExt.toMono(singleCellCall(cell, clientCall).toSingle());
    }

    private <T> Observable<T> singleCellCallWithNoDeadline(Cell cell, ClientCall<T> clientCall) {
        return callToCell(cell, connector, JobManagementServiceGrpc::newStub,
                (client, streamObserver) -> clientCall.accept(wrapWithNoDeadline(client), streamObserver));
    }

    private interface ClientCall<T> extends BiConsumer<JobManagementServiceStub, StreamObserver<T>> {
        // generics sanity
    }
}


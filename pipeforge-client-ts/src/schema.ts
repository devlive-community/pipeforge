/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Generated from pipeforge-server/openapi.json by scripts/generate.mjs; do not edit.
// Regenerate with: pnpm --filter @pipeforge/client generate

export interface paths {
    "/api/v1/cluster": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get the cluster
         * @description The master and every registered worker with its slots, labels, resources and last heartbeat, and how many slots runs hold. HTTP 503 without a master or when it cannot be reached.
         */
        get: operations["getCluster"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cluster/failover": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Hand the lead over
         * @description Asks the leading master to give its lease up: a standby master takes over within a third of master.leader.lease, the runs go on there from their last checkpoints, and the old leader stops so that its supervisor starts it again as a standby. HTTP 409 when the masters elect no leader (no metastore), 503 without a master or when it cannot be reached.
         */
        post: operations["failoverMaster"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cluster/workers/{id}/drain": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Drain a worker
         * @description Gives a worker no new tasks: its free slots are offered to no run, and the tasks running on it finish where they are. The workers of the cluster show it as draining until it is uncordoned or a new master leads. HTTP 404 for a worker the master does not know.
         */
        post: operations["drainWorker"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cluster/workers/{id}/uncordon": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Uncordon a worker
         * @description Gives a drained worker new tasks again. HTTP 404 for a worker the master does not know.
         */
        post: operations["uncordonWorker"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/components/{type}/{identifier}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Describe a component
         * @description The options of a source, transform or sink: types, defaults, requirements and the rules between them.
         */
        get: operations["getComponent"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/errors": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List error codes
         * @description Every registered error code with its message and hint templates.
         */
        get: operations["listErrorCodes"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/explain": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Explain a job
         * @description The logical plan of the job: settings, variables, nodes, options with their origin, schemas and warnings. An invalid job is an error response.
         */
        post: operations["explainJob"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/expressions/evaluate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Evaluate an expression
         * @description Type-checks the expression against the given columns and evaluates it on their values. Mistakes are PF-PLAN-0007, failures while evaluating PF-DATA-0006.
         */
        post: operations["evaluateExpression"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/functions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List built-in functions
         * @description Every built-in function with its signatures, NULL behavior and examples.
         */
        get: operations["listFunctions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/jobs/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Preview a job
         * @description Runs the job in the server up to a node, or up to its sink for a dry run, with the sinks replaced by a buffer, at parallelism 1, and returns the first rows of every table there. It stops at the row limit, at the end of the input or at the timeout; nothing is written to any target.
         */
        post: operations["previewJob"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/jobs/schema": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get the job schema
         * @description The JSON Schema (draft 2020-12) of job definitions, with the options of every installed source, transform and sink, for completion in editors. Validation stays with validateJob.
         */
        get: operations["getJobSchema"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/jobs/validate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Validate a job
         * @description Parses the definition, resolves its variables, checks the plugins, options, graph and schemas. Problems are part of the result, not an error response.
         */
        post: operations["validateJob"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/plugins": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List installed plugins
         * @description Every usable plugin with its components, and the packages that were rejected.
         */
        get: operations["listPlugins"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List runs
         * @description Runs newest first, optionally of some states or of one job. A page ends with the token of the next one in next.
         */
        get: operations["listRuns"];
        put?: never;
        /**
         * Run a job
         * @description Plans the job, records a run and submits it to the master. An invalid job is an error response and no run is recorded. The run starts in QUEUE; GET /api/v1/runs/{id} follows it.
         */
        post: operations["submitRun"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get a run
         * @description The run with its job definition and every change of its state, oldest first.
         */
        get: operations["getRun"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{id}/analysis": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get the analysis of a run
         * @description What limited the run and where (design 29): job-level figures, node-level figures and the bottleneck. Kept when the run ends.
         */
        get: operations["getRunAnalysis"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Cancel a run
         * @description Asks the master to cancel a run that has not ended; the run moves to CANCELLING, then CANCELLED. An ended run is returned as it is.
         */
        post: operations["cancelRun"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{id}/logs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Read the log of a task
         * @description A part of a task's log, through the master from the worker it ran on. offset in the answer is where the next read continues, so following a log means reading again from it; eof is true once the log was read to its end and the task has ended.
         */
        get: operations["readRunLog"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{id}/metrics": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get the metrics of a run
         * @description Rows, rates, waits, memory, splits and the counters of every node: from the master while the run runs, else the last sample the server kept (design 15).
         */
        get: operations["getRunMetrics"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{id}/metrics/history": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get the metrics of a run over time
         * @description The samples the server kept while the run ran (pipeforge.master.metrics-interval), oldest first; rates follow from two samples.
         */
        get: operations["getRunMetricsHistory"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{id}/retry": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Retry a run
         * @description Starts the next attempt of a run that failed, timed out or was cancelled, whatever env.retry allows: from its last completed checkpoint or from the beginning, with the same variables and plugin versions. The run moves back to QUEUE; its attempts are listed by GET /api/v1/runs/{id}.
         */
        post: operations["retryRun"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{id}/splits": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List the splits of a run
         * @description Every split the source assigned, with its reader and whether the reader finished; empty once the master no longer has the run.
         */
        get: operations["listRunSplits"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{id}/tasks": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List the tasks of a run
         * @description Every task with its state and worker, to read its log; empty once the master no longer has the run.
         */
        get: operations["listRunTasks"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/version": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get the server version
         * @description The PipeForge version, the connector API version and the Java runtime.
         */
        get: operations["getVersion"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        Attempt: {
            /** Format: int32 */
            attempt?: number;
            /** Format: date-time */
            endedAt?: string;
            error?: components["schemas"]["RunError"];
            restorePath?: string;
            /** Format: date-time */
            startedAt?: string;
            /** @enum {string} */
            status?: "QUEUE" | "RUNNING" | "SUCCESS" | "FAILED" | "CANCELLING" | "CANCELLED" | "TIMEOUT" | "RETRY_WAIT";
        };
        CancelRequest: {
            reason?: string;
        };
        ClusterView: {
            master?: components["schemas"]["Master"];
            /** Format: int32 */
            slots?: number;
            /** Format: int32 */
            slotsUsed?: number;
            /** Format: double */
            usage?: number;
            workers?: components["schemas"]["Worker"][];
        };
        Column: {
            comment?: string;
            name?: string;
            nullable?: boolean;
            type?: string;
        };
        ColumnValue: {
            name?: string;
            type?: string;
            value?: string;
        };
        ComponentInfo: {
            conditions?: components["schemas"]["ConditionInfo"][];
            exclusive?: string[][];
            identifier?: string;
            nativeNamespaces?: string[];
            options?: components["schemas"]["OptionInfo"][];
            plugin?: string;
            type?: string;
        };
        ComponentRef: {
            identifier?: string;
            type?: string;
        };
        ConditionInfo: {
            option?: string;
            required?: string[];
            value?: unknown;
        };
        DatasourceInfo: {
            type?: string;
            versions?: string[];
        };
        DirtySample: {
            /** Format: date-time */
            at?: string;
            code?: string;
            message?: string;
            node?: string;
            row?: string;
            table?: string;
        };
        ErrorCodeInfo: {
            category?: string;
            code?: string;
            deprecated?: boolean;
            hint?: string;
            kind?: string;
            message?: string;
            name?: string;
            params?: string[];
        };
        ErrorInfo: {
            causeChain?: string[];
            code?: string;
            hint?: string;
            /** @enum {string} */
            kind?: "TRANSIENT" | "DATA" | "FATAL";
            message?: string;
            name?: string;
            params?: {
                [key: string]: unknown;
            };
            scope?: {
                [key: string]: unknown;
            };
        };
        EvaluationRequest: {
            columns?: components["schemas"]["ColumnValue"][];
            expression: string;
            timezone?: string;
        };
        EvaluationResult: {
            deterministic?: boolean;
            type?: string;
            value?: string;
        };
        ExampleInfo: {
            expression?: string;
            result?: string;
        };
        ExplainDocument: {
            job?: components["schemas"]["Job"];
            nodes?: components["schemas"]["Node"][];
            variables?: {
                [key: string]: string;
            };
            warnings?: string[];
        };
        ExplainResult: {
            plan?: components["schemas"]["ExplainDocument"];
            text?: string;
        };
        Failover: {
            master?: string;
        };
        FunctionInfo: {
            category?: string;
            description?: string;
            deterministic?: boolean;
            examples?: components["schemas"]["ExampleInfo"][];
            name?: string;
            nulls?: string;
            signatures?: string[];
            syntax?: string;
        };
        Job: {
            delivery?: string;
            description?: string;
            engine?: string;
            mode?: string;
            modeDerived?: boolean;
            name?: string;
            /** Format: int32 */
            parallelism?: number;
            timezone?: string;
        };
        JobRequest: {
            definition: string;
            /** Format: date-time */
            scheduledTime?: string;
            variables?: {
                [key: string]: string;
            };
        };
        JsonNode: {
            array?: boolean;
            bigDecimal?: boolean;
            bigInteger?: boolean;
            binary?: boolean;
            boolean?: boolean;
            container?: boolean;
            double?: boolean;
            embeddedValue?: boolean;
            empty?: boolean;
            float?: boolean;
            floatingPointNumber?: boolean;
            int?: boolean;
            integralNumber?: boolean;
            long?: boolean;
            missingNode?: boolean;
            /** @enum {string} */
            nodeType?: "ARRAY" | "BINARY" | "BOOLEAN" | "MISSING" | "NULL" | "NUMBER" | "OBJECT" | "POJO" | "STRING";
            null?: boolean;
            number?: boolean;
            object?: boolean;
            pojo?: boolean;
            short?: boolean;
            string?: boolean;
            /** @deprecated */
            textual?: boolean;
            valueNode?: boolean;
        };
        LogChunk: {
            eof?: boolean;
            /** Format: int64 */
            offset?: number;
            text?: string;
        };
        Master: {
            address?: string;
            protocol?: string;
            /** Format: date-time */
            startedAt?: string;
            version?: string;
        };
        Node: {
            boundedness?: string;
            id?: string;
            inputs?: string[];
            inputsImplied?: boolean;
            inputTables?: components["schemas"]["Table"][];
            kind?: string;
            options?: components["schemas"]["Option"][];
            outputTables?: components["schemas"]["Table"][];
            /** Format: int32 */
            parallelism?: number;
            plugin?: string;
            type?: string;
        };
        NodeMetrics: {
            /** Format: int64 */
            bytesIn?: number;
            /** Format: int64 */
            bytesOut?: number;
            node?: string;
            /** Format: int64 */
            rowsDirty?: number;
            /** Format: int64 */
            rowsExpanded?: number;
            /** Format: int64 */
            rowsFiltered?: number;
            /** Format: int64 */
            rowsIn?: number;
            /** Format: int64 */
            rowsOut?: number;
        };
        Option: {
            key?: string;
            origin?: string;
            sensitive?: boolean;
            value?: unknown;
        };
        OptionInfo: {
            defaultValue?: unknown;
            deprecatedKeys?: string[];
            description?: string;
            group?: string;
            key?: string;
            /** Format: double */
            max?: number;
            /** Format: double */
            min?: number;
            requirement?: string;
            sensitive?: boolean;
            since?: string;
            type?: string;
            values?: string[];
        };
        PluginInfo: {
            apiVersion?: string;
            components?: components["schemas"]["ComponentRef"][];
            datasource?: components["schemas"]["DatasourceInfo"];
            description?: string;
            drivers?: string[];
            id?: string;
            license?: string;
            name?: string;
            vendor?: string;
            version?: string;
        };
        PluginList: {
            plugins?: components["schemas"]["PluginInfo"][];
            unavailable?: components["schemas"]["UnavailablePlugin"][];
        };
        PreviewColumn: {
            name?: string;
            type?: string;
        };
        PreviewRequest: {
            definition: string;
            /** Format: int32 */
            limit?: number;
            node?: string;
            /** Format: date-time */
            scheduledTime?: string;
            /** Format: int32 */
            timeoutSeconds?: number;
            variables?: {
                [key: string]: string;
            };
        };
        PreviewResult: {
            /** Format: int64 */
            elapsedMs?: number;
            error?: components["schemas"]["ErrorInfo"];
            node?: string;
            /** @enum {string} */
            outcome?: "COMPLETE" | "LIMIT" | "TIMEOUT" | "FAILED";
            /** Format: int64 */
            rowsRead?: number;
            tables?: components["schemas"]["PreviewTable"][];
        };
        PreviewTable: {
            columns?: components["schemas"]["PreviewColumn"][];
            rows?: unknown[][];
            table?: string;
            truncated?: boolean;
        };
        RetryRequest: {
            /** @enum {string} */
            from?: "CHECKPOINT" | "SCRATCH";
        };
        Run: {
            /** Format: int32 */
            attempt?: number;
            /** Format: date-time */
            endedAt?: string;
            error?: components["schemas"]["RunError"];
            id?: string;
            job?: string;
            /** Format: date-time */
            retryAt?: string;
            /** Format: int64 */
            rowsRead?: number;
            /** Format: int64 */
            rowsWritten?: number;
            /** Format: date-time */
            startedAt?: string;
            /** @enum {string} */
            status?: "QUEUE" | "RUNNING" | "SUCCESS" | "FAILED" | "CANCELLING" | "CANCELLED" | "TIMEOUT" | "RETRY_WAIT";
            /** Format: date-time */
            submittedAt?: string;
            submittedBy?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        RunDetail: {
            attempts?: components["schemas"]["Attempt"][];
            definition?: string;
            run?: components["schemas"]["Run"];
            transitions?: components["schemas"]["Transition"][];
        };
        RunError: {
            code?: string;
            message?: string;
        };
        RunMetrics: {
            /** Format: date-time */
            at?: string;
            /** Format: int64 */
            backpressuredMs?: number;
            /** Format: int64 */
            checkpointFailures?: number;
            dirtySamples?: components["schemas"]["DirtySample"][];
            /** Format: int64 */
            elapsedMs?: number;
            /** Format: int64 */
            exchangedBytes?: number;
            /** Format: int64 */
            lastCheckpointDurationMs?: number;
            /** Format: int64 */
            lastCheckpointSize?: number;
            /** Format: int64 */
            lastCompletedCheckpointId?: number;
            /** Format: int64 */
            memoryBytes?: number;
            nodes?: components["schemas"]["NodeMetrics"][];
            /** Format: int32 */
            parallelism?: number;
            /** Format: int32 */
            queueCapacity?: number;
            /** Format: int32 */
            queueDepth?: number;
            /** Format: int64 */
            readerWaitMs?: number;
            /** Format: double */
            readRowsPerSecond?: number;
            /** Format: int64 */
            rowsDirty?: number;
            /** Format: int64 */
            rowsExpanded?: number;
            /** Format: int64 */
            rowsFailed?: number;
            /** Format: int64 */
            rowsFiltered?: number;
            /** Format: int64 */
            rowsInFlight?: number;
            /** Format: int64 */
            rowsRead?: number;
            /** Format: int64 */
            rowsWritten?: number;
            /** Format: int32 */
            splitsFinished?: number;
            /** Format: int32 */
            splitsTotal?: number;
            /** Format: int32 */
            tasksRunning?: number;
            /** Format: int64 */
            throttleWaitMs?: number;
            /** Format: double */
            writeRowsPerSecond?: number;
            /** Format: int64 */
            writerWaitMs?: number;
        };
        RunPage: {
            next?: string;
            runs?: components["schemas"]["Run"][];
        };
        Sample: {
            /** Format: date-time */
            at?: string;
            /** Format: int64 */
            elapsedMs?: number;
            /** Format: int64 */
            rowsDirty?: number;
            /** Format: int64 */
            rowsFailed?: number;
            /** Format: int64 */
            rowsRead?: number;
            /** Format: int64 */
            rowsWritten?: number;
        };
        Slot: {
            allocated?: boolean;
            /** Format: int32 */
            index?: number;
            job?: string;
        };
        Split: {
            node?: string;
            split?: string;
            state?: string;
            /** Format: int32 */
            subtask?: number;
        };
        SubmitRequest: {
            definition: string;
            /** Format: date-time */
            scheduledTime?: string;
            variables?: {
                [key: string]: string;
            };
        };
        Table: {
            columns?: components["schemas"]["Column"][];
            id?: string;
            partitionKeys?: string[];
            primaryKey?: string[];
        };
        Task: {
            /** Format: int32 */
            attempt?: number;
            state?: string;
            /** Format: int32 */
            subtask?: number;
            vertex?: string;
            worker?: string;
        };
        Transition: {
            /** Format: date-time */
            at?: string;
            /** Format: int32 */
            attempt?: number;
            /** @enum {string} */
            from?: "QUEUE" | "RUNNING" | "SUCCESS" | "FAILED" | "CANCELLING" | "CANCELLED" | "TIMEOUT" | "RETRY_WAIT";
            reason?: string;
            /** @enum {string} */
            to?: "QUEUE" | "RUNNING" | "SUCCESS" | "FAILED" | "CANCELLING" | "CANCELLED" | "TIMEOUT" | "RETRY_WAIT";
        };
        UnavailablePlugin: {
            location?: string;
            reason?: string;
        };
        ValidationResult: {
            errors?: components["schemas"]["ErrorInfo"][];
            valid?: boolean;
            warnings?: string[];
        };
        VersionInfo: {
            connectorApi?: string;
            java?: string;
            version?: string;
        };
        Worker: {
            /** Format: int32 */
            cpuCores?: number;
            dataAddress?: string;
            draining?: boolean;
            id?: string;
            labels?: {
                [key: string]: string;
            };
            /** Format: int64 */
            managedBytes?: number;
            /** Format: int64 */
            networkBytes?: number;
            resourceGroup?: string;
            rpcAddress?: string;
            /** Format: int64 */
            sinceHeartbeatMs?: number;
            slots?: components["schemas"]["Slot"][];
            /** Format: int64 */
            taskHeapBytes?: number;
            /** Format: int32 */
            tasksRunning?: number;
            version?: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getCluster: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ClusterView"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    failoverMaster: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Accepted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Failover"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    drainWorker: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    uncordonWorker: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    getComponent: {
        parameters: {
            query?: {
                /** @description Plugin id or id@version; required when several plugins provide it */
                plugin?: string | null;
            };
            header?: never;
            path: {
                /** @description The component identifier, e.g. jdbc */
                identifier: string;
                /** @description source, transform or sink */
                type: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ComponentInfo"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    listErrorCodes: {
        parameters: {
            query?: never;
            header?: {
                "Accept-Language"?: string | null;
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorCodeInfo"][];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    explainJob: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JobRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ExplainResult"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    evaluateExpression: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["EvaluationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EvaluationResult"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    listFunctions: {
        parameters: {
            query?: never;
            header?: {
                "Accept-Language"?: string | null;
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FunctionInfo"][];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    previewJob: {
        parameters: {
            query?: never;
            header?: {
                "Accept-Language"?: string | null;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PreviewRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PreviewResult"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    getJobSchema: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        [key: string]: unknown;
                    };
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    validateJob: {
        parameters: {
            query?: never;
            header?: {
                "Accept-Language"?: string | null;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JobRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ValidationResult"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    listPlugins: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PluginList"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    listRuns: {
        parameters: {
            query?: {
                /** @description The name of a job */
                job?: string | null;
                /** @description Runs per page, at most 200 */
                limit?: number;
                /** @description The token of a page, from next */
                page?: string | null;
                /** @description States to list; every state when none */
                status?: ("QUEUE" | "RUNNING" | "SUCCESS" | "FAILED" | "CANCELLING" | "CANCELLED" | "TIMEOUT" | "RETRY_WAIT")[] | null;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunPage"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    submitRun: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SubmitRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Run"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    getRun: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunDetail"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    getRunAnalysis: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["JsonNode"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    cancelRun: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["CancelRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Run"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    readRunLog: {
        parameters: {
            query: {
                /** @description The attempt */
                attempt?: number;
                /** @description Only entries with this text */
                contains?: string;
                /** @description Only entries of this level or above: TRACE, DEBUG, INFO, WARN, ERROR */
                level?: string | null;
                /** @description The most bytes; 0 for 64KB */
                maxBytes?: number;
                /** @description Where to start; negative counts back from the end */
                offset?: number;
                /** @description The subtask */
                subtask: number;
                /** @description The vertex of the task, as in the tasks of the run */
                vertex: string;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["LogChunk"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    getRunMetrics: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunMetrics"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    getRunMetricsHistory: {
        parameters: {
            query?: {
                /** @description Samples at most, up to 10000 */
                limit?: number;
                /** @description Only samples from this time on */
                since?: string | null;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Sample"][];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    retryRun: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["RetryRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Run"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    listRunSplits: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Split"][];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    listRunTasks: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Task"][];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
    getVersion: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["VersionInfo"];
                };
            };
            /** @description The request is invalid (PF-CONF-0007) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description The job, expression or plugin options are invalid */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
            /** @description Internal error (PF-SYS-0001) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorInfo"];
                };
            };
        };
    };
}

/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Generated from pipeforge-server/openapi.json by scripts/generate.mjs; do not edit.
// Regenerate with: pnpm --filter @pipeforge/client generate

export interface paths {
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
        Table: {
            columns?: components["schemas"]["Column"][];
            id?: string;
            partitionKeys?: string[];
            primaryKey?: string[];
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
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
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

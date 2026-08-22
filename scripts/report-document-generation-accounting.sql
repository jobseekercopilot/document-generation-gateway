\set ON_ERROR_STOP on

-- Internal operations report. Never expose this result through the browser API.
WITH outputs AS (
    SELECT operation.id AS operation_id,
           operation.state AS operation_state,
           operation.failure_code,
           operation.created_at,
           item.key AS document_type,
           item.value AS result
      FROM generation_operations operation
      CROSS JOIN LATERAL jsonb_each(
          COALESCE(operation.data_json::jsonb -> 'outputResults', '{}'::jsonb)
      ) AS item
), facts AS (
    SELECT operation_id,
           document_type,
           result ->> 'status' AS output_status,
           COALESCE((result ->> 'regeneration')::boolean, false) AS regeneration,
           result ->> 'billingOutcome' AS billing_outcome,
           result #>> '{generation,recovery,finalSource}' AS generation_source,
           COALESCE((result #>> '{generation,recovery,providerAttemptCount}')::integer,
                    (result #>> '{generation,audit,providerAttemptCount}')::integer,
                    0) AS provider_attempts,
           COALESCE((result #>> '{generation,recovery,automaticRetryCount}')::integer,
                    (result #>> '{generation,audit,automaticRetryCount}')::integer,
                    0) AS automatic_retries,
           COALESCE((result #>> '{generation,usage,inputTokens}')::bigint, 0) AS input_tokens,
           COALESCE((result #>> '{generation,usage,outputTokens}')::bigint, 0) AS output_tokens,
           COALESCE((result #>> '{generation,usage,totalTokens}')::bigint, 0) AS total_tokens,
           COALESCE((result #>> '{generation,audit,estimatedCostMicroUsd}')::bigint, 0)
               AS estimated_cost_micro_usd,
           result #>> '{generation,audit,modelId}' AS model_id,
           result #>> '{generation,audit,pricingVersion}' AS pricing_version,
           failure_code,
           created_at
      FROM outputs
)
SELECT document_type,
       COUNT(*) FILTER (WHERE billing_outcome = 'COMMITTED') AS delivered,
       COUNT(*) FILTER (WHERE billing_outcome = 'COMMITTED' AND regeneration) AS regenerations,
       COUNT(*) FILTER (WHERE output_status = 'FAILED') AS failures,
       SUM(automatic_retries) AS automatic_retries,
       SUM(input_tokens) AS input_tokens,
       SUM(output_tokens) AS output_tokens,
       SUM(total_tokens) AS total_tokens,
       SUM(estimated_cost_micro_usd) AS estimated_cost_micro_usd,
       ROUND(AVG(estimated_cost_micro_usd)
             FILTER (WHERE billing_outcome = 'COMMITTED'), 2)
           AS average_estimated_cost_micro_usd_per_delivery
  FROM facts
 GROUP BY document_type
 ORDER BY document_type;

-- Provider/source detail used to explain cost or retry changes without exposing it to users.
WITH outputs AS (
    SELECT item.key AS document_type, item.value AS result
      FROM generation_operations operation
      CROSS JOIN LATERAL jsonb_each(
          COALESCE(operation.data_json::jsonb -> 'outputResults', '{}'::jsonb)
      ) AS item
)
SELECT document_type,
       COALESCE(result #>> '{generation,recovery,finalSource}', 'NOT_AVAILABLE')
           AS generation_source,
       COALESCE(result #>> '{generation,audit,modelId}', 'NOT_AVAILABLE') AS model_id,
       COALESCE(result #>> '{generation,audit,pricingVersion}', 'NOT_AVAILABLE')
           AS pricing_version,
       COUNT(*) AS outputs,
       SUM(COALESCE((result #>> '{generation,audit,estimatedCostMicroUsd}')::bigint, 0))
           AS estimated_cost_micro_usd
  FROM outputs
 GROUP BY document_type, generation_source, model_id, pricing_version
 ORDER BY document_type, generation_source, model_id, pricing_version;

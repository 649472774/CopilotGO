package com.tongxie.copilotgo.data.tools.schema

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.DecimalNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ValueNode
import com.networknt.schema.InputFormat
import com.networknt.schema.FailFastAssertionException
import com.networknt.schema.OutputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaException
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.tongxie.copilotgo.data.tools.ToolException
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.math.BigInteger
import java.io.IOException
import java.net.URISyntaxException
import java.util.concurrent.CancellationException

internal object ToolSchemaCompiler {
    fun compile(schema: JsonObject): ValidatedToolSchema = schemaBoundary(
        "无法安全编译工具 Schema，已拒绝该工具"
    ) {
        val definition = BoundedSchemaJson.schema(schema)
        BoundedSchemaJson.text(definition)
        val preflight = ToolSchemaPreflight(definition).inspect()
        val metaSchema = TrustedToolMetaSchemas.get(preflight.dialect)
        // getSchema() constructs validators; it does not validate keyword values against the metaschema.
        // Checking every subschema also covers the explicitly supported Draft-07 $defs extension.
        preflight.subschemas.forEach {
            if (metaSchema.validate(it.toString(), InputFormat.JSON).isNotEmpty()) {
                invalidToolSchema()
            }
        }
        val registry = SchemaRegistry.withDefaultDialect(preflight.dialect.version) { builder ->
            builder.nodeReader { it.jsonMapper(ToolSchemaJsonMapper.mapper) }
            builder.schemaCacheEnabled(false)
            builder.schemaLoader { it.fetchRemoteResources(false).block { true } }
        }
        val evaluator = registry.getSchema(preflight.normalized.toString()).also { it.initializeValidators() }
        ValidatedToolSchema(definition, evaluator)
    }
}

/**
 * Both arguments and structured results use a 32 KiB / depth-16 / 2,048-node limit.
 * Validation is synchronous, bounded CPU work; callers should keep it off the UI thread.
 */
internal class ValidatedToolSchema internal constructor(
    val definition: JsonObject,
    private val evaluator: Schema
) {
    fun validate(value: JsonElement) {
        schemaBoundary("无法安全校验工具数据，已拒绝本次调用或结果") {
            val text = BoundedSchemaJson.instance(value)
            if (!evaluator.validate(text, InputFormat.JSON, OutputFormat.BOOLEAN)) {
                schemaFailure(ToolProblemCode.SCHEMA, "工具参数或结构化结果不符合 Schema 约束")
            }
        }
    }
}

private object TrustedToolMetaSchemas {
    private val draft07 by lazy { load(ToolSchemaDialect.DRAFT_07) }
    private val draft2020 by lazy { load(ToolSchemaDialect.DRAFT_2020_12) }

    fun get(dialect: ToolSchemaDialect): Schema = when (dialect) {
        ToolSchemaDialect.DRAFT_07 -> draft07
        ToolSchemaDialect.DRAFT_2020_12 -> draft2020
    }

    private fun load(dialect: ToolSchemaDialect): Schema {
        // This separate registry can read only these library-bundled, fixed metaschemas.
        // The allow check runs before networknt's built-in classpath mapping; no URI from a
        // tool is supplied as a schema location here, and no remote loader is enabled.
        val allowed = when (dialect) {
            ToolSchemaDialect.DRAFT_07 -> setOf(dialect.uri)
            ToolSchemaDialect.DRAFT_2020_12 -> setOf(
                dialect.uri,
                "https://json-schema.org/draft/2020-12/meta/core",
                "https://json-schema.org/draft/2020-12/meta/applicator",
                "https://json-schema.org/draft/2020-12/meta/unevaluated",
                "https://json-schema.org/draft/2020-12/meta/validation",
                "https://json-schema.org/draft/2020-12/meta/meta-data",
                "https://json-schema.org/draft/2020-12/meta/format-annotation",
                "https://json-schema.org/draft/2020-12/meta/content"
            )
        }
        val registry = SchemaRegistry.withDefaultDialect(dialect.version) { builder ->
            builder.nodeReader { it.jsonMapper(ToolSchemaJsonMapper.mapper) }
            builder.schemaLoader { loader ->
                loader.fetchRemoteResources(false).allow { it.toString() in allowed }
            }
        }
        // Automatic preloading can suppress failures. Explicit initialization must succeed before use.
        return registry.getSchema(SchemaLocation.of(dialect.version.dialectId))
            .also { it.initializeValidators() }
    }
}

private object ToolSchemaJsonMapper {
    // Only JSON numeric decoding is customized; neither registry installs a custom URI mapper or loader.
    val mapper: JsonMapper = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .nodeFactory(DecimalNumbers)
        .build()

    // networknt's multipleOf uses doubleValue() for non-DecimalNodes, including BigIntegerNodes.
    // Decimal-backed numbers preserve exact JSON numeric semantics, including integers above 2^53.
    private object DecimalNumbers : JsonNodeFactory() {
        override fun numberNode(value: BigInteger?): ValueNode =
            if (value == null) nullNode() else DecimalNode(BigDecimal(value))
    }
}

private inline fun <T> schemaBoundary(message: String, action: () -> T): T = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (safe: ToolException) {
    throw safe
} catch (_: SchemaException) {
    schemaFailure(ToolProblemCode.SCHEMA, message)
} catch (_: FailFastAssertionException) {
    schemaFailure(ToolProblemCode.SCHEMA, message)
} catch (_: IOException) {
    schemaFailure(ToolProblemCode.SCHEMA, message)
} catch (_: IllegalArgumentException) {
    schemaFailure(ToolProblemCode.SCHEMA, message)
} catch (_: ArithmeticException) {
    schemaFailure(ToolProblemCode.SCHEMA, message)
} catch (_: URISyntaxException) {
    schemaFailure(ToolProblemCode.SCHEMA, message)
}

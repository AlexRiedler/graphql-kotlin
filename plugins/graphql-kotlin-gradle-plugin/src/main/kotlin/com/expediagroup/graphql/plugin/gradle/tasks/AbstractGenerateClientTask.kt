/*
 * Copyright 2021 Expedia, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.expediagroup.graphql.plugin.gradle.tasks

import com.expediagroup.graphql.plugin.gradle.actions.GenerateClientAction
import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer
import com.expediagroup.graphql.plugin.gradle.config.GraphQLScalar
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.workers.ClassLoaderWorkerSpec
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

/**
 * Generate GraphQL Kotlin client and corresponding data classes based on the provided GraphQL queries.
 */
@Suppress("UnstableApiUsage")
abstract class AbstractGenerateClientTask : DefaultTask() {

    @get:Classpath
    val pluginClasspath: ConfigurableFileCollection = project.objects.fileCollection()

    /**
     * Path to GraphQL schema file that will be used to generate client code.
     *
     * **Required Property**: [schemaFileName] or [schemaFile] has to be provided.
     * **Command line property is**: `schemaFileName`.
     */
    @Input
    @Optional
    @Option(option = "schemaFileName", description = "path to GraphQL schema file that will be used to generate the client code")
    val schemaFileName: Property<String> = project.objects.property(String::class.java)

    /**
     * GraphQL schema file that will be used to generate client code.
     *
     * **Required Property**: [schemaFileName] or [schemaFile] has to be provided.
     */
    @InputFile
    @Optional
    val schemaFile: RegularFileProperty = project.objects.fileProperty()

    /**
     * Target package name for generated code.
     *
     * **Required Property**
     * **Command line property is**: `packageName`.
     */
    @Input
    @Option(option = "packageName", description = "target package name to use for generated classes")
    val packageName: Property<String> = project.objects.property(String::class.java)

    /**
     * Boolean flag indicating whether selection of deprecated fields is allowed or not.
     *
     * **Default value is:** `false`.
     * **Command line property is**: `allowDeprecatedFields`.
     */
    @Input
    @Optional
    @Option(option = "allowDeprecatedFields", description = "boolean flag indicating whether selection of deprecated fields is allowed or not")
    val allowDeprecatedFields: Property<Boolean> = project.objects.property(Boolean::class.java)

    /**
     * List of custom GraphQL scalar converters.
     *
     * ```kotlin
     * customScalars.add(GraphQLScalar("UUID", "java.util.UUID", "com.expediagroup.graphql.examples.client.UUIDScalarConverter"))
     * )
     */
    @Input
    @Optional
    val customScalars: ListProperty<GraphQLScalar> = project.objects.listProperty(GraphQLScalar::class.java)

    /**
     * Directory containing GraphQL queries. Defaults to `src/main/resources` when generating main sources and `src/test/resources`
     * when generating test client.
     *
     * Instead of specifying a directory you can also specify list of query file by using `queryFiles` property instead.
     */
    @Input
    @Optional
    @Option(option = "queryFileDirectory", description = "directory containing query files")
    val queryFileDirectory: Property<String> = project.objects.property(String::class.java)

    /**
     * List of query files to be processed. Instead of a list of files to be processed you can also specify [queryFileDirectory] directory
     * containing all the files. If this property is specified it will take precedence over the corresponding directory property.
     */
    @InputFiles
    @Optional
    val queryFiles: ConfigurableFileCollection = project.objects.fileCollection()

    @Input
    @Optional
    @Option(option = "serializer", description = "JSON serializer that will be used to generate the data classes.")
    val serializer: Property<GraphQLSerializer> = project.objects.property(GraphQLSerializer::class.java)

    @OutputDirectory
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    @Inject
    abstract fun getWorkerExecutor(): WorkerExecutor

    init {
        group = "GraphQL"
        description = "Generate HTTP client from the specified GraphQL queries."

        allowDeprecatedFields.convention(false)
        customScalars.convention(emptyList())
        serializer.convention(GraphQLSerializer.JACKSON)
        queryFileDirectory.convention("${project.projectDir}/src/main/resources")
        outputDirectory.convention(project.layout.buildDirectory.dir("generated/source/graphql/main"))
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    @TaskAction
    fun generateGraphQLClientAction() {
        logger.debug("generating GraphQL client")

        val graphQLSchema = when {
            schemaFile.isPresent -> schemaFile.get().asFile
            schemaFileName.isPresent -> File(schemaFileName.get())
            else -> throw RuntimeException("schema not available")
        }
        if (!graphQLSchema.isFile) {
            throw RuntimeException("specified schema file does not exist")
        }

        val targetPackage = packageName.orNull ?: throw RuntimeException("package not specified")
        val targetQueryFiles: List<File> = when {
            queryFiles.files.isNotEmpty() -> queryFiles.files.toList()
            queryFileDirectory.isPresent ->
                File(queryFileDirectory.get())
                    .listFiles { file -> file.extension == "graphql" }
                    ?.toList() ?: throw RuntimeException("exception while looking up the query files")
            else -> throw RuntimeException("no query files found")
        }

        if (targetQueryFiles.isEmpty()) {
            throw RuntimeException("no query files specified")
        }

        val targetDirectory = outputDirectory.get().asFile
        if (!targetDirectory.isDirectory && !targetDirectory.mkdirs()) {
            throw RuntimeException("failed to generate generated source directory = $targetDirectory")
        }

        logConfiguration(graphQLSchema, targetQueryFiles)
        val workQueue: WorkQueue = getWorkerExecutor().classLoaderIsolation { workerSpec: ClassLoaderWorkerSpec ->
            workerSpec.classpath.from(pluginClasspath)
            logger.debug("worker classpath: \n${workerSpec.classpath.files.joinToString("\n")}")
        }

        workQueue.submit(GenerateClientAction::class.java) { parameters ->
            parameters.packageName.set(targetPackage)
            parameters.allowDeprecated.set(allowDeprecatedFields)
            parameters.customScalars.set(customScalars)
            parameters.serializer.set(serializer)
            parameters.schemaFile.set(graphQLSchema)
            parameters.queryFiles.set(targetQueryFiles)
            parameters.targetDirectory.set(targetDirectory)
        }
        workQueue.await()
        logger.debug("successfully generated GraphQL HTTP client")
    }

    private fun logConfiguration(schema: File, queryFiles: List<File>) {
        logger.debug("GraphQL Client generator configuration:")
        logger.debug("  schema file = ${schema.path}")
        logger.debug("  queries")
        queryFiles.forEach {
            logger.debug("    - ${it.name}")
        }
        logger.debug("  packageName = $packageName")
        logger.debug("  allowDeprecatedFields = $allowDeprecatedFields")
        logger.debug("  converters")
        customScalars.get().forEach { (customScalar, type, converter) ->
            logger.debug("    - custom scalar = $customScalar")
            logger.debug("      |- type = $type")
            logger.debug("      |- converter = $converter")
        }
        logger.debug("")
        logger.debug("-- end GraphQL Client generator configuration --")
    }
}

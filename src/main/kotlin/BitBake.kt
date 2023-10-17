/*
 * Copyright (C) 2023 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 * Copyright (C) 2023 Double Open Oy (see <https://www.doubleopen.org/>)
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
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.plugins.packagemanagers.bitbake

import java.io.File

import kotlin.io.path.createTempFile

import org.apache.logging.log4j.kotlin.logger

import org.ossreviewtoolkit.analyzer.AbstractPackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.plugins.packagemanagers.spdx.SpdxDocumentFile
import org.ossreviewtoolkit.utils.common.ProcessCapture
import org.ossreviewtoolkit.utils.common.getCommonParentFile
import org.ossreviewtoolkit.utils.common.withoutPrefix

/**
 * A package manager that uses OpenEmbedded's "bitbake" tool to create SPDX SBOMs [1][2] e.g. for Yocto distributions,
 * and post-processes these into ORT analyzer results.
 *
 * [1]: https://docs.yoctoproject.org/dev/dev-manual/sbom.html
 * [2]: https://dev.to/angrymane/create-spdx-with-yocto-2od9
 */
class BitBake(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, analysisRoot, analyzerConfig, repoConfig) {
    class Factory : AbstractPackageManagerFactory<BitBake>("BitBake") {
        override val globsForDefinitionFiles = listOf("*.bb")

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = BitBake(type, analysisRoot, analyzerConfig, repoConfig)
    }

    private val scriptFile by lazy { extractResourceToTempFile(BITBAKE_SCRIPT_NAME).apply { setExecutable(true) } }
    private val confFile by lazy { extractResourceToTempFile(SPDX_CONF_NAME) }

    private val spdxManager by lazy { SpdxDocumentFile(name, analysisRoot, analyzerConfig, repoConfig) }

    override fun resolveDependencies(definitionFiles: List<File>, labels: Map<String, String>): PackageManagerResult {
        val commonDefinitionDir = getCommonParentFile(definitionFiles)
        val workingDir = requireNotNull(commonDefinitionDir.searchUpwardsForFile(INIT_SCRIPT_NAME)) {
            "No '$INIT_SCRIPT_NAME' script file found for directory '$commonDefinitionDir'."
        }

        val deployDirs = mutableSetOf<File>()

        definitionFiles.forEach { definitionFile ->
            val target = definitionFile.nameWithoutExtension.substringBeforeLast('_')

            val deployDir = getDeployDir(workingDir, target)
            deployDirs += deployDir

            val spdxFile = deployDir.findSpdxFiles().find { it.name == "recipe-$target.spdx.json" }
            if (spdxFile != null) {
                logger.info { "Not creating SPDX file for target '$target' as it already exists at '$spdxFile'." }
            } else {
                createSpdx(workingDir, target)
            }
        }

        if (!scriptFile.delete()) logger.warn { "Unable to delete temporary script file '$scriptFile'." }
        if (!confFile.delete()) logger.warn { "Unable to delete temporary conf file '$confFile'." }

        val commonDeployDir = deployDirs.singleOrNull() ?: getCommonParentFile(deployDirs)
        val spdxFiles = commonDeployDir.findSpdxFiles()

        return spdxManager.resolveDependencies(spdxFiles.toList(), labels)
    }

    override fun resolveDependencies(definitionFile: File, labels: Map<String, String>): List<ProjectAnalyzerResult> =
        throw NotImplementedError("This function is not supported for $managerName.")

    private fun getDeployDir(workingDir: File, target: String): File {
        val bitbakeEnv = runBitBake(workingDir, "-e", target)
        return bitbakeEnv.stdout.lineSequence().mapNotNull { it.withoutPrefix("DEPLOY_DIR=") }.first()
            .let { File(it.removeSurrounding("\"")) }
    }

    private fun createSpdx(workingDir: File, target: String) =
        runBitBake(workingDir, "-r", confFile.absolutePath, "-c", "create_spdx", target)

    private fun File.findSpdxFiles() = resolve("spdx").walk().filter { it.isFile && it.name.endsWith(".spdx.json") }

    internal fun runBitBake(workingDir: File, vararg args: String): ProcessCapture =
        ProcessCapture(scriptFile.absolutePath, workingDir.absolutePath, *args, workingDir = workingDir)
            .requireSuccess()

    private fun extractResourceToTempFile(resourceName: String): File {
        val prefix = resourceName.substringBefore('.')
        val suffix = resourceName.substringAfter(prefix)
        val scriptFile = createTempFile(prefix, suffix).toFile()
        val script = checkNotNull(javaClass.getResource("/$resourceName")).readText()

        return scriptFile.apply { writeText(script) }
    }
}

private const val INIT_SCRIPT_NAME = "oe-init-build-env"
private const val BITBAKE_SCRIPT_NAME = "bitbake.sh"
private const val SPDX_CONF_NAME = "spdx.conf"

private fun File.searchUpwardsForFile(searchFileName: String): File? {
    if (!isDirectory) return null

    var currentDir: File? = absoluteFile

    while (currentDir != null && !currentDir.resolve(searchFileName).isFile) {
        currentDir = currentDir.parentFile
    }

    return currentDir
}
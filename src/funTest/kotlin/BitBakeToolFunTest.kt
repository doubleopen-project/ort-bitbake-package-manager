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

import io.kotest.core.spec.style.WordSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.string.shouldMatch

import org.ossreviewtoolkit.analyzer.managers.create

class BitBakeToolFunTest : WordSpec({
    "BitBake" should {
        "get the version correctly" {
            val bitBake = create("BitBake") as BitBake

            val bitBakeProcess = bitBake.runBitBake(tempdir(), "--version")
            val version = bitBakeProcess.stdout.lineSequence().first().substringAfterLast(' ')

            version shouldMatch "\\d+\\.\\d+\\.\\d+"
        }
    }
})

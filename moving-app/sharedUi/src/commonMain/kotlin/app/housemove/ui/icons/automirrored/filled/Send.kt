/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Copied from androidx.compose.material:material-icons-core:1.7.8 (its sources jar). Changed: the package,
// which was androidx.compose.material.icons.automirrored.filled. Licence: moving-app/licenses/Material-Icons-Apache-2.0.txt

package app.housemove.ui.icons.automirrored.filled

import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.materialIcon
import app.housemove.ui.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.AutoMirrored.Filled.Send: ImageVector
    get() {
        if (_send != null) {
            return _send!!
        }
        _send = materialIcon(name = "AutoMirrored.Filled.Send", autoMirror = true) {
            materialPath {
                moveTo(2.01f, 21.0f)
                lineTo(23.0f, 12.0f)
                lineTo(2.01f, 3.0f)
                lineTo(2.0f, 10.0f)
                lineToRelative(15.0f, 2.0f)
                lineToRelative(-15.0f, 2.0f)
                close()
            }
        }
        return _send!!
    }

private var _send: ImageVector? = null

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
// Copied from androidx.compose.material:material-icons-extended:1.7.8 (its sources jar). Changed: the package,
// which was androidx.compose.material.icons.filled. Licence: moving-app/licenses/Material-Icons-Apache-2.0.txt

package app.housemove.ui.icons.filled

import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.materialIcon
import app.housemove.ui.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.SyncAlt: ImageVector
    get() {
        if (_syncAlt != null) {
            return _syncAlt!!
        }
        _syncAlt = materialIcon(name = "Filled.SyncAlt") {
            materialPath {
                moveTo(18.0f, 12.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(-4.0f, -4.0f)
                lineToRelative(0.0f, 3.0f)
                lineToRelative(-15.0f, 0.0f)
                lineToRelative(0.0f, 2.0f)
                lineToRelative(15.0f, 0.0f)
                close()
            }
            materialPath {
                moveTo(6.0f, 12.0f)
                lineToRelative(-4.0f, 4.0f)
                lineToRelative(4.0f, 4.0f)
                lineToRelative(0.0f, -3.0f)
                lineToRelative(15.0f, 0.0f)
                lineToRelative(0.0f, -2.0f)
                lineToRelative(-15.0f, 0.0f)
                close()
            }
        }
        return _syncAlt!!
    }

private var _syncAlt: ImageVector? = null

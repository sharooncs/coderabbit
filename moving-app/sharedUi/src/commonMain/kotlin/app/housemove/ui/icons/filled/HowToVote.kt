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

public val Icons.Filled.HowToVote: ImageVector
    get() {
        if (_howToVote != null) {
            return _howToVote!!
        }
        _howToVote = materialIcon(name = "Filled.HowToVote") {
            materialPath {
                moveTo(18.0f, 13.0f)
                horizontalLineToRelative(-0.68f)
                lineToRelative(-2.0f, 2.0f)
                horizontalLineToRelative(1.91f)
                lineTo(19.0f, 17.0f)
                lineTo(5.0f, 17.0f)
                lineToRelative(1.78f, -2.0f)
                horizontalLineToRelative(2.05f)
                lineToRelative(-2.0f, -2.0f)
                lineTo(6.0f, 13.0f)
                lineToRelative(-3.0f, 3.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 1.99f, 2.0f)
                lineTo(19.0f, 22.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.89f, 2.0f, -2.0f)
                verticalLineToRelative(-4.0f)
                lineToRelative(-3.0f, -3.0f)
                close()
                moveTo(17.0f, 7.95f)
                lineToRelative(-4.95f, 4.95f)
                lineToRelative(-3.54f, -3.54f)
                lineToRelative(4.95f, -4.95f)
                lineTo(17.0f, 7.95f)
                close()
                moveTo(12.76f, 2.29f)
                lineTo(6.39f, 8.66f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.41f)
                lineToRelative(4.95f, 4.95f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                lineToRelative(6.36f, -6.36f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
                lineTo(14.16f, 2.3f)
                curveToRelative(-0.38f, -0.4f, -1.01f, -0.4f, -1.4f, -0.01f)
                close()
            }
        }
        return _howToVote!!
    }

private var _howToVote: ImageVector? = null

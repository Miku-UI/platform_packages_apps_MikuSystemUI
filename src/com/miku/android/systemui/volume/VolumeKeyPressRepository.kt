/*
 * Copyright (C) 2026 Miku UI
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

package com.miku.android.systemui.volume

import android.view.KeyEvent
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class VolumeKeyPressRepository
@Inject
constructor(
    commandQueue: CommandQueue,
    @Application applicationScope: CoroutineScope,
) {
    val volumeKeyPress: StateFlow<VolumeKeyPress> =
        conflatedCallbackFlow {
                val callback =
                    object : CommandQueue.Callbacks {
                        override fun handleSystemKey(event: KeyEvent) {
                            val press =
                                when (event.keyCode) {
                                    KeyEvent.KEYCODE_VOLUME_UP -> VolumeKeyPress.Up
                                    KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKeyPress.Down
                                    else -> return
                                }
                            when (event.action) {
                                KeyEvent.ACTION_DOWN ->
                                    trySendWithFailureLogging(press, TAG, "volume key down")
                                KeyEvent.ACTION_UP ->
                                    trySendWithFailureLogging(
                                        VolumeKeyPress.None,
                                        TAG,
                                        "volume key up",
                                    )
                            }
                        }
                    }
                trySendWithFailureLogging(VolumeKeyPress.None, TAG, "init volumeKeyPress")
                commandQueue.addCallback(callback)
                awaitClose { commandQueue.removeCallback(callback) }
            }
            .stateIn(applicationScope, SharingStarted.Eagerly, VolumeKeyPress.None)

    companion object {
        private const val TAG = "VolumeKeyPressRepository"
    }
}

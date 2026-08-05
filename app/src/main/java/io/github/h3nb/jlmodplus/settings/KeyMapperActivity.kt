/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.settings

import android.content.Intent
import android.os.Bundle
import android.util.SparseIntArray
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.GsonBuilder
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.ConfigActivity
import io.github.h3nb.jlmodplus.config.ProfileModel
import io.github.h3nb.jlmodplus.config.ProfilesManager
import io.github.h3nb.jlmodplus.ui.WindowInsetsPolicy
import io.github.h3nb.jlmodplus.util.SparseIntArrayAdapter
import java.io.File
import javax.microedition.lcdui.keyboard.KeyMapper

class KeyMapperActivity : AppCompatActivity() {
    companion object {
        private const val KEY_SAVE = "KEY_MAP_SAVE"
    }

    private val defaultKeyMap = KeyMapper.getDefaultKeyMap()
    private lateinit var androidToMIDP: SparseIntArray
    private lateinit var params: ProfileModel
    private lateinit var uiState: KeyMapperUiState
    private var canvasKey: Int = 0
    private lateinit var menuWarningCallback: OnBackPressedCallback
    private lateinit var mappingDialogBackCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsPolicy.enableEdgeToEdge(window)
        val path = intent.dataString
        if (path == null) {
            Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        params = requireNotNull(ProfilesManager.loadConfig(File(path)))
        androidToMIDP = restoreKeyMap(savedInstanceState)
        uiState = KeyMapperUiState()

        setContent {
            KeyMapperScreen(
                state = uiState,
                onBack = { onBackPressedDispatcher.onBackPressed() },
                onResetMapping = ::resetMapping,
                onKeyClick = ::showMappingDialog,
                onDismissMapping = ::hideMappingDialog,
                onDismissMenuWarning = { uiState.menuWarningVisible = false },
                onConfirmMenuWarning = {
                    uiState.menuWarningVisible = false
                    save()
                    finish()
                },
            )
        }

        menuWarningCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                uiState.menuWarningVisible = true
            }
        }
        onBackPressedDispatcher.addCallback(this, menuWarningCallback)
        mappingDialogBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                hideMappingDialog()
            }
        }
        // Add this after the menu-warning callback so the transient mapping
        // surface has priority while it is visible.
        onBackPressedDispatcher.addCallback(this, mappingDialogBackCallback)
        updateMenuWarningCallback()
    }

    private fun restoreKeyMap(savedInstanceState: Bundle?): SparseIntArray {
        if (savedInstanceState == null) {
            return params.keyMappings?.clone() ?: defaultKeyMap.clone()
        }
        val save = savedInstanceState.getString(KEY_SAVE)
        return when {
            save == null -> defaultKeyMap.clone()
            save.isEmpty() -> params.keyMappings?.clone() ?: defaultKeyMap.clone()
            else -> GsonBuilder()
                .registerTypeAdapter(SparseIntArray::class.java, SparseIntArrayAdapter())
                .create()
                .fromJson(save, SparseIntArray::class.java)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (!equalMaps(androidToMIDP, defaultKeyMap) && !equalMaps(params.keyMappings, androidToMIDP)) {
            val currMap = GsonBuilder()
                .registerTypeAdapter(SparseIntArray::class.java, SparseIntArrayAdapter())
                .create()
                .toJson(androidToMIDP)
            outState.putString(KEY_SAVE, currMap)
        } else if (!equalMaps(androidToMIDP, defaultKeyMap)) {
            outState.putString(KEY_SAVE, "")
        }
        super.onSaveInstanceState(outState)
    }

    private fun showMappingDialog(canvasKey: Int) {
        this.canvasKey = canvasKey
        val index = androidToMIDP.indexOfValue(canvasKey)
        val keyName = if (index < 0) {
            getString(R.string.mapping_dialog_key_not_specified)
        } else {
            KeyEvent.keyCodeToString(androidToMIDP.keyAt(index))
        }
        uiState.showMappingDialog(getString(R.string.mapping_dialog_message, keyName))
        mappingDialogBackCallback.isEnabled = true
    }

    private fun hideMappingDialog() {
        uiState.hideMappingDialog()
        mappingDialogBackCallback.isEnabled = false
    }

    private fun deleteDuplicates(value: Int) {
        for (i in androidToMIDP.size() - 1 downTo 0) {
            if (androidToMIDP.valueAt(i) == value) {
                androidToMIDP.removeAt(i)
            }
        }
        updateMenuWarningCallback()
    }

    private fun resetMapping() {
        androidToMIDP = defaultKeyMap.clone()
        updateMenuWarningCallback()
    }

    override fun finish() {
        if (::androidToMIDP.isInitialized && ::params.isInitialized) {
            save()
        }
        super.finish()
    }

    private fun save() {
        var newMap: SparseIntArray? = androidToMIDP
        val oldMap = params.keyMappings
        if (equalMaps(newMap, defaultKeyMap)) {
            newMap = null
        }
        if (!equalMaps(oldMap, newMap)) {
            params.keyMappings = newMap
            ProfilesManager.saveConfig(params)
        }
    }

    private fun updateMenuWarningCallback() {
        if (::menuWarningCallback.isInitialized) {
            menuWarningCallback.isEnabled = androidToMIDP.indexOfValue(KeyMapper.KEY_OPTIONS_MENU) < 0
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::uiState.isInitialized && uiState.isMappingDialogVisible()
            && event.action == KeyEvent.ACTION_DOWN
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                -> Unit

                else -> {
                    deleteDuplicates(canvasKey)
                    androidToMIDP.put(event.keyCode, canvasKey)
                    updateMenuWarningCallback()
                    hideMappingDialog()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun equalMaps(map1: SparseIntArray?, map2: SparseIntArray?): Boolean {
        if (map1 === map2) return true
        if (map1 == null || map2 == null || map1.size() != map2.size()) return false
        for (i in 0 until map1.size()) {
            if (map2.keyAt(i) != map1.keyAt(i) || map2.valueAt(i) != map1.valueAt(i)) return false
        }
        return true
    }
}

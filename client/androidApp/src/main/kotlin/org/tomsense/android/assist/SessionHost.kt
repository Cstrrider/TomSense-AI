package org.tomsense.android.assist

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Enough of an Activity to host Compose in a window that has none.
 *
 * A `VoiceInteractionSession` owns its own window, and Compose refuses to run
 * in a view tree without a lifecycle, a saved-state registry and a ViewModel
 * store — it throws at first composition rather than degrading, and doing that
 * inside a power-button hold would crash the assistant every time it was
 * invoked. So the session supplies them itself.
 *
 * The lifecycle is driven manually from the session callbacks. Getting the
 * order wrong is the trap: `performRestore` MUST run before the registry is
 * read, and the state must reach RESUMED before the view is attached or
 * composition never starts.
 */
class SessionHost : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun create() {
        // Before anything reads the registry, and exactly once.
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    /** Call once the content view exists, or composition never starts. */
    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun pause() {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    /** Attach the three owners Compose looks for on the view tree. */
    fun attachTo(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}

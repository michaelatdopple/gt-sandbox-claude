package com.dopple.webview.ui.love

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dopple.webview.R
import com.dopple.webview.cache.BundleCacheManager
import com.dopple.webview.data.ManifestRepository
import com.dopple.webview.data.Manifest as ActivityManifest
import com.dopple.webview.download.BundleDownloader
import com.dopple.webview.ui.gallery.PowerBarView
import com.dopple.webview.ui.gallery.RetroColors
import com.dopple.webview.ui.orchestrator.AppOrchestrator
import com.dopple.webview.services.DoppleServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.libsdl.app.LoveFragmentContext
import org.libsdl.app.SDL
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLSurface
import java.io.File

/**
 * Fragment for playing Love2D games via SDL SurfaceView.
 *
 * Handles manifest fetching, .love bundle download/caching, and loading UI.
 * The actual SDL SurfaceView integration is stubbed until the love rig
 * (love-qv5 / love-pim) provides JNI entry points.
 *
 * Navigation:
 * - Press A -> Return to referrer (Gallery or Scanner)
 */
class Love2dGameFragment : Fragment() {

    companion object {
        private const val TAG = "Love2dGameFragment"
        const val ARG_MANIFEST_URL = "manifestUrl"

        /** Check if a URL points to the local asset server. */
        fun isAssetUrl(url: String): Boolean =
            url.startsWith("http://127.0.0.1:8088/")

        /** Extract the asset path from a local asset server URL. */
        fun assetPathFromUrl(url: String): String =
            url.removePrefix("http://127.0.0.1:8088/")

        private var nativeLibLoaded = false

        init {
            try {
                System.loadLibrary("love")
                nativeLibLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                // Expected until love rig produces liblove.so
                Log.w(TAG, "liblove.so not available: ${e.message}")
            }
        }
    }

    private val viewModel: Love2dGameViewModel by viewModels()

    // Views
    private var surfaceContainer: FrameLayout? = null
    private var sdlSurface: SDLSurface? = null
    private var loadingOverlay: LinearLayout? = null
    private var loadingText: TextView? = null
    private var loadingProgress: PowerBarView? = null
    private var loadingGameName: TextView? = null
    private var errorView: LinearLayout? = null
    private var errorText: TextView? = null

    // Dependencies (same injection pattern as GameFragment)
    private val manifestRepository = ManifestRepository()
    private val bundleDownloader = BundleDownloader()
    private var cacheManager: BundleCacheManager? = null
    private var loveSetupPending = false
    private var loveInitialized = false
    private var surfaceReady = false
    private var pendingLovePath: String? = null
    var orchestrator: AppOrchestrator? = null
        set(value) {
            field = value
            if (value != null && loveSetupPending) {
                loveSetupPending = false
                loadGame()
            }
        }
    var serviceManager: DoppleServiceManager? = null

    // Get manifest URL from arguments
    private val manifestUrl: String?
        get() = arguments?.getString(ARG_MANIFEST_URL)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_love2d_game, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Consume all window insets for fullscreen
        view.setOnApplyWindowInsetsListener { _, _ ->
            WindowInsets.CONSUMED
        }
        view.requestApplyInsets()

        // Bind views
        surfaceContainer = view.findViewById(R.id.love2d_surface_container)
        loadingOverlay = view.findViewById(R.id.loading_overlay)
        loadingText = view.findViewById(R.id.loading_text)
        loadingProgress = view.findViewById(R.id.loading_progress)
        loadingGameName = view.findViewById(R.id.loading_game_name)
        errorView = view.findViewById(R.id.error_view)
        errorText = view.findViewById(R.id.error_text)

        // Initialize cache manager
        cacheManager = BundleCacheManager(requireContext())

        // Create SDL surface for Love2D rendering.
        // Must be added to the layout before nativeInit so SDL can get a valid Surface.
        if (nativeLibLoaded) {
            setupSdlSurface()
        }

        // Configure loading progress
        loadingProgress?.configure("", "LOADING", RetroColors.CYAN)
        loadingProgress?.startPulse()

        // Observe state
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }

        // Focus for input
        view.isFocusableInTouchMode = true
        view.requestFocus()

        // Load game (or wait for orchestrator)
        if (orchestrator != null) {
            loadGame()
        } else {
            loveSetupPending = true
            Log.d(TAG, "Love2D setup pending - waiting for orchestrator injection")
        }
    }

    override fun onPause() {
        super.onPause()
        pauseLove()
    }

    override fun onResume() {
        super.onResume()
        resumeLove()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        quitLove()

        SDLActivity.setFragmentSurface(null)
        sdlSurface = null
        surfaceReady = false
        pendingLovePath = null
        surfaceContainer = null
        loadingOverlay = null
        loadingText = null
        loadingProgress = null
        loadingGameName = null
        errorView = null
        errorText = null
        cacheManager = null
    }

    private fun loadGame() {
        val url = manifestUrl ?: return

        lifecycleScope.launch {
            if (!isAdded) return@launch

            val result = manifestRepository.fetchManifest(url, context)

            if (!isAdded) return@launch

            result.onSuccess { manifest ->
                viewModel.setGameName(manifest.activityName)
                downloadLoveBundle(manifest)
            }.onFailure { error ->
                viewModel.setError(error.message ?: "Failed to load game")
            }
        }
    }

    /**
     * Downloads and caches the .love bundle file.
     *
     * Key difference from GameFragment: .love files are NOT extracted via ZipExtractor.
     * Love2D loads .love files directly via PhysFS, so we download and cache the
     * .love file as-is.
     */
    private fun downloadLoveBundle(manifest: ActivityManifest) {
        lifecycleScope.launch {
            try {
                if (!isAdded || context == null) return@launch

                val ctx = requireContext()
                val cache = cacheManager ?: BundleCacheManager(ctx)
                val projectDir = cache.getBundleDirectory(manifest.projectId)

                // Check cache — don't use validateCachedBundle() since that checks
                // for index.html which is WebView-specific. For Love2D we just check
                // that the game.love file exists.
                val shouldUseCache = cache.getCachedVersion(manifest.projectId) == manifest.version
                    && cache.isBundleCached(manifest.projectId)

                if (shouldUseCache) {
                    Log.d(TAG, "Using cached Love2D bundle")
                    val loveFile = File(projectDir, "game.love")
                    if (loveFile.exists()) {
                        onBundleReady(loveFile.absolutePath)
                        return@launch
                    }
                }

                // Clean and prepare directory
                try {
                    if (projectDir.exists()) projectDir.deleteRecursively()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not clean previous bundle", e)
                }

                if (!projectDir.mkdirs() && !projectDir.exists()) {
                    viewModel.setError("Could not create storage")
                    return@launch
                }

                val bundleUrl = manifest.bundleUrl
                if (bundleUrl.isNullOrBlank()) {
                    viewModel.setError("No bundle URL in manifest")
                    return@launch
                }

                val loveFile = File(projectDir, "game.love")
                val loadResult = if (isAssetUrl(bundleUrl)) {
                    copyBundleFromAssets(ctx, bundleUrl, loveFile)
                } else {
                    downloadBundle(bundleUrl, loveFile)
                }
                if (!loadResult) return@launch

                cache.setCachedVersion(manifest.projectId, manifest.version)
                onBundleReady(loveFile.absolutePath)

            } catch (e: Exception) {
                Log.e(TAG, "Bundle processing failed", e)
                viewModel.setError("Failed to load: ${e.message}")
            }
        }
    }

    /** Copy a .love bundle from APK assets to internal storage. Returns true on success. */
    private fun copyBundleFromAssets(
        ctx: android.content.Context,
        bundleUrl: String,
        loveFile: File
    ): Boolean {
        return try {
            val assetPath = assetPathFromUrl(bundleUrl)
            Log.d(TAG, "Copying bundled game from assets: $assetPath")
            ctx.assets.open(assetPath).use { input ->
                loveFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            viewModel.setError("Failed to load bundled game: ${e.message}")
            false
        }
    }

    /** Download a .love bundle from the network. Returns true on success. */
    private suspend fun downloadBundle(bundleUrl: String, loveFile: File): Boolean {
        val downloadResult = bundleDownloader.download(
            url = bundleUrl,
            destination = loveFile,
            onProgress = { _, _ -> }
        )

        if (!isAdded) {
            loveFile.delete()
            return false
        }

        downloadResult.onFailure { error ->
            viewModel.setError(error.message ?: "Download failed")
            return false
        }
        return true
    }

    private fun onBundleReady(lovePath: String) {
        Log.d(TAG, "Love2D bundle ready: $lovePath")
        viewModel.setLoading(false)
        if (surfaceReady) {
            initLove(lovePath)
        } else {
            Log.d(TAG, "Surface not ready yet, deferring initLove")
            pendingLovePath = lovePath
        }
    }

    private fun setupSdlSurface() {
        val container = surfaceContainer ?: return

        // SDL3 JNI setup — populates mActivityClass and other cached jclass refs.
        SDL.setupJNI()
        // Initialize all SDL managers (controller, audio, etc.) — must happen before
        // any native SDL calls. This resets statics, so set the surface AFTER.
        SDL.initialize()
        // Wrap the context with LoveFragmentContext which adds SDLActivity-specific
        // methods (getImmersiveMode, getDPIScale, etc.) that love's JNI code expects.
        SDL.setContext(LoveFragmentContext(requireContext()))

        val surface = SDLSurface(requireContext())
        sdlSurface = surface

        // Register the surface with SDL so getNativeSurface() returns a valid Surface.
        SDLActivity.setFragmentSurface(surface)

        // Listen for surface readiness to start Love2D at the right time.
        surface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                Log.d(TAG, "SDL surface created")
            }

            override fun surfaceChanged(
                holder: android.view.SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                Log.d(TAG, "SDL surface ready: ${width}x${height}")
                surfaceReady = true
                val pending = pendingLovePath
                if (pending != null) {
                    pendingLovePath = null
                    initLove(pending)
                }
            }

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                Log.d(TAG, "SDL surface destroyed")
                surfaceReady = false
            }
        })

        container.addView(
            surface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    // --- JNI declarations (implemented in love rig: src/common/android_fragment.cpp) ---
    private external fun nativeInit(lovePath: String)
    private external fun nativePause()
    private external fun nativeResume()
    private external fun nativeQuit()

    // --- SDL Lifecycle ---

    private fun initLove(lovePath: String) {
        if (!nativeLibLoaded) {
            Log.w(TAG, "initLove: liblove.so not loaded, skipping")
            return
        }
        if (!surfaceReady) {
            Log.w(TAG, "initLove: surface not ready, deferring")
            pendingLovePath = lovePath
            return
        }
        Log.d(TAG, "initLove: $lovePath")

        nativeInit(lovePath)
        loveInitialized = true
    }

    private fun pauseLove() {
        if (!nativeLibLoaded || !loveInitialized) return
        Log.d(TAG, "pauseLove")
        nativePause()
    }

    private fun resumeLove() {
        if (!nativeLibLoaded || !loveInitialized) return
        Log.d(TAG, "resumeLove")
        nativeResume()
    }

    private fun quitLove() {
        if (!nativeLibLoaded || !loveInitialized) return
        loveInitialized = false
        Log.d(TAG, "quitLove: dispatching to IO thread")
        lifecycleScope.launch(Dispatchers.IO) {
            nativeQuit()
        }
    }

    private fun updateUI(state: Love2dGameUiState) {
        if (state.error != null) {
            loadingOverlay?.visibility = View.GONE
            errorView?.visibility = View.VISIBLE
            errorText?.text = state.error
        } else if (state.isLoading) {
            loadingOverlay?.visibility = View.VISIBLE
            errorView?.visibility = View.GONE
            loadingGameName?.text = state.gameName
        } else {
            // Fade out loading overlay
            loadingOverlay?.animate()
                ?.alpha(0f)
                ?.setDuration(300)
                ?.withEndAction {
                    loadingOverlay?.visibility = View.GONE
                }
                ?.start()
            errorView?.visibility = View.GONE
        }
    }
}

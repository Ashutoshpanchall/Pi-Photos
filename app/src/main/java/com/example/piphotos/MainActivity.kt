package com.example.piphotos

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.databinding.ActivityMainBinding
import com.example.piphotos.media.LocalMediaIndex
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), WelcomeFragment.Listener {

    private lateinit var binding: ActivityMainBinding
    private var currentTabId = R.id.navigation_photos

    private var activeFragment: Fragment? = null

    private lateinit var timelineFragment: Fragment
    private lateinit var albumsFragment: Fragment
    private lateinit var backupFragment: Fragment
    private lateinit var profileFragment: Fragment

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setBackgroundDrawableResource(R.color.bg_page)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // LAYER 1: Synchronous initial status bar inset on fragment container
        val initialStatusHeight = WindowInsetsHelper.getStatusBarHeight(this)
        binding.fragmentContainer.setPadding(0, initialStatusHeight, 0, 0)

        // LAYER 2: Reactive WindowInsets listener for dynamic display cutouts & navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            val effectiveStatusBarTop = maxOf(statusBars, displayCutout, systemBars, initialStatusHeight)

            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val systemNavBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val effectiveNavBarBottom = maxOf(navBars, systemNavBars)

            val leftInset = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).left

            binding.fragmentContainer.setPadding(0, effectiveStatusBarTop, 0, 0)
            binding.bottomNavigation.setPadding(0, 0, 0, effectiveNavBarBottom)
            binding.navigationRail.setPadding(leftInset, effectiveStatusBarTop, 0, effectiveNavBarBottom)
            insets
        }

        ViewCompat.requestApplyInsets(binding.mainRoot)

        // LAYER 3: Dark status bar icons on Light theme, White on AMOLED dark theme
        val isDark = ThemeManager.isDarkMode(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }

        if (savedInstanceState != null) {
            currentTabId = savedInstanceState.getInt(KEY_CURRENT_TAB, R.id.navigation_photos)
        }

        val isWide = resources.configuration.screenWidthDp >= 600
        val hasAccess = PermissionHelper.hasAnyMediaAccess(this)
        if (!hasAccess) {
            binding.bottomNavigation.visibility = View.GONE
            binding.navigationRail.visibility = View.GONE
            val existingWelcome = supportFragmentManager.findFragmentByTag(TAG_WELCOME)
            if (existingWelcome == null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, WelcomeFragment.newInstance(), TAG_WELCOME)
                    .commitNow()
            }
            setupBackNavigation()
        } else {
            binding.bottomNavigation.visibility = if (isWide) View.GONE else View.VISIBLE
            binding.navigationRail.visibility = if (isWide) View.VISIBLE else View.GONE
            setupFragments(savedInstanceState)
            setupBottomNavigation()
            setupBackNavigation()

            if (savedInstanceState != null) {
                if (isWide) {
                    binding.navigationRail.selectedItemId = currentTabId
                } else {
                    binding.bottomNavigation.selectedItemId = currentTabId
                }
            } else {
                lifecycleScope.launch {
                    LocalMediaIndex.getInstance(applicationContext).rebuildIndex()
                }
            }
        }
    }

    override fun onPermissionResult(granted: Boolean, isPartial: Boolean) {
        val fm = supportFragmentManager
        val welcome = fm.findFragmentByTag(TAG_WELCOME)
        if (welcome != null) {
            fm.beginTransaction().remove(welcome).commitNow()
        }
        if (granted || isPartial) {
            val isWide = resources.configuration.screenWidthDp >= 600
            binding.bottomNavigation.visibility = if (isWide) View.GONE else View.VISIBLE
            binding.navigationRail.visibility = if (isWide) View.VISIBLE else View.GONE
            setupFragments(null)
            setupBottomNavigation()
            lifecycleScope.launch {
                LocalMediaIndex.getInstance(applicationContext).rebuildIndex()
            }
        }
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        if (savedInstanceState == null) {
            timelineFragment = TimelineFragment.newInstance()
            albumsFragment = AlbumsFragment.newInstance()
            backupFragment = BackupFragment.newInstance()
            profileFragment = ProfileFragment.newInstance()

            fm.beginTransaction()
                .add(R.id.fragmentContainer, timelineFragment, TAG_PHOTOS)
                .add(R.id.fragmentContainer, albumsFragment, TAG_FOLDERS).hide(albumsFragment)
                .add(R.id.fragmentContainer, backupFragment, TAG_BACKUP).hide(backupFragment)
                .add(R.id.fragmentContainer, profileFragment, TAG_YOU).hide(profileFragment)
                .commitNow()
            activeFragment = timelineFragment
        } else {
            timelineFragment = fm.findFragmentByTag(TAG_PHOTOS) ?: TimelineFragment.newInstance()
            albumsFragment = fm.findFragmentByTag(TAG_FOLDERS) ?: AlbumsFragment.newInstance()
            backupFragment = fm.findFragmentByTag(TAG_BACKUP) ?: BackupFragment.newInstance()
            profileFragment = fm.findFragmentByTag(TAG_YOU) ?: ProfileFragment.newInstance()

            val target = when (currentTabId) {
                R.id.navigation_photos -> timelineFragment
                R.id.navigation_folders -> albumsFragment
                R.id.navigation_backup -> backupFragment
                R.id.navigation_you -> profileFragment
                else -> timelineFragment
            }
            activeFragment = target

            val tx = fm.beginTransaction()
            listOf(timelineFragment, albumsFragment, backupFragment, profileFragment).forEach { f ->
                if (f == target) {
                    tx.show(f)
                } else {
                    tx.hide(f)
                }
            }
            tx.commitAllowingStateLoss()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fm = supportFragmentManager
                if (fm.findFragmentByTag(TAG_WELCOME) != null) {
                    finish()
                    return
                }

                if (currentTabId != R.id.navigation_photos) {
                    val isWide = resources.configuration.screenWidthDp >= 600
                    if (isWide) {
                        binding.navigationRail.selectedItemId = R.id.navigation_photos
                    } else {
                        binding.bottomNavigation.selectedItemId = R.id.navigation_photos
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val isDark = ThemeManager.isDarkMode(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }

        if (PermissionHelper.hasAnyMediaAccess(this)) {
            val fm = supportFragmentManager
            val welcome = fm.findFragmentByTag(TAG_WELCOME)
            if (welcome != null) {
                fm.beginTransaction().remove(welcome).commitAllowingStateLoss()
                val isWide = resources.configuration.screenWidthDp >= 600
                binding.bottomNavigation.visibility = if (isWide) View.GONE else View.VISIBLE
                binding.navigationRail.visibility = if (isWide) View.VISIBLE else View.GONE
                lifecycleScope.launch {
                    LocalMediaIndex.getInstance(applicationContext).rebuildIndex()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, currentTabId)
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            switchToTab(item.itemId)
            true
        }

        binding.navigationRail.setOnItemSelectedListener { item ->
            switchToTab(item.itemId)
            true
        }
    }

    private fun switchToTab(tabId: Int) {
        currentTabId = tabId
        val target = when (tabId) {
            R.id.navigation_photos -> timelineFragment
            R.id.navigation_folders -> albumsFragment
            R.id.navigation_backup -> backupFragment
            R.id.navigation_you -> profileFragment
            else -> timelineFragment
        }

        if (activeFragment == target) return

        val tx = supportFragmentManager.beginTransaction()
        activeFragment?.let { tx.hide(it) }
        tx.show(target).commitAllowingStateLoss()
        activeFragment = target
    }

    companion object {
        private const val KEY_CURRENT_TAB = "key_current_tab"
        private const val TAG_PHOTOS = "tab_photos"
        private const val TAG_FOLDERS = "tab_folders"
        private const val TAG_BACKUP = "tab_backup"
        private const val TAG_YOU = "tab_you"
        private const val TAG_WELCOME = "screen_welcome"
    }
}

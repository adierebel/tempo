package com.cappielloantonio.tempo.ui.activity;

import android.content.Context;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.splashscreen.SplashScreen;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.BuildConfig;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.broadcast.receiver.ConnectivityStatusBroadcastReceiver;
import com.cappielloantonio.tempo.databinding.ActivityMainBinding;
import com.cappielloantonio.tempo.github.utils.UpdateUtil;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.ui.activity.base.BaseActivity;
import com.cappielloantonio.tempo.ui.dialog.ConnectionAlertDialog;
import com.cappielloantonio.tempo.ui.dialog.GithubTempoUpdateDialog;
import com.cappielloantonio.tempo.ui.dialog.ServerUnreachableDialog;
import com.cappielloantonio.tempo.ui.fragment.DownloadFragment;
import com.cappielloantonio.tempo.ui.fragment.HomeFragment;
import com.cappielloantonio.tempo.ui.fragment.LibraryFragment;
import com.cappielloantonio.tempo.ui.fragment.PlayerBottomSheetFragment;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.viewmodel.MainViewModel;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.color.DynamicColors;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.Objects;
import java.util.concurrent.ExecutionException;

@UnstableApi
public class MainActivity extends BaseActivity {
    private static final String TAG = "MainActivityLogs";

    public ActivityMainBinding bind;
    private MainViewModel mainViewModel;

    private FragmentManager fragmentManager;
    private NavHostFragment navHostFragment;
    private BottomNavigationView bottomNavigationView;
    public NavController navController;
    private BottomSheetBehavior bottomSheetBehavior;
    private boolean isLandscape = false;
    private boolean isLoggedIn = false;
    private boolean isLastPage = false;

    private Fragment selectedFragment = null;
    private String prevFragmentTag = null;
    private HomeFragment homeFragment = null;
    private LibraryFragment libraryFragment = null;
    private DownloadFragment downloadFragment = null;

    ConnectivityStatusBroadcastReceiver connectivityStatusBroadcastReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        DynamicColors.applyToActivityIfAvailable(this);

        super.onCreate(savedInstanceState);

        bind = ActivityMainBinding.inflate(getLayoutInflater());
        View view = bind.getRoot();
        setContentView(view);

        mainViewModel = new ViewModelProvider(this).get(MainViewModel.class);

        connectivityStatusBroadcastReceiver = new ConnectivityStatusBroadcastReceiver(this);
        connectivityStatusReceiverManager(true);

        isLandscape = (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        isLoggedIn = (Preferences.getPassword() != null || (Preferences.getToken() != null && Preferences.getSalt() != null));

        init();
        checkConnectionType();
        getOpenSubsonicExtensions();
        checkTempoUpdate();
    }

    @Override
    protected void onStart() {
        super.onStart();
        initService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pingServer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        connectivityStatusReceiverManager(false);
        bind = null;
    }

    @Override
    public void onBackPressed() {
        if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED)
            collapseBottomSheetDelayed();
        else {
            if (isLastPage) {
                finish();
            } else {
                super.onBackPressed();
            }
        }
    }

    public void init() {
        fragmentManager = getSupportFragmentManager();

        initBottomSheet();
        initNavigation();

        if (isLoggedIn) {
            goFromLogin();
        } else {
            goToLogin();
        }

        // Set bottom navigation height
        if (isLandscape) {
            ViewGroup.LayoutParams layoutParams = bottomNavigationView.getLayoutParams();
            Rect windowRect = new Rect();
            bottomNavigationView.getWindowVisibleDisplayFrame(windowRect);
            layoutParams.width = windowRect.height();
            bottomNavigationView.setLayoutParams(layoutParams);
        }
    }

    // BOTTOM SHEET/NAVIGATION
    private void initBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(findViewById(R.id.player_bottom_sheet));
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback);
        fragmentManager.beginTransaction().replace(R.id.player_bottom_sheet, new PlayerBottomSheetFragment(), "PlayerBottomSheet").commit();

        checkBottomSheetAfterStateChanged();
    }

    public void setBottomSheetInPeek(Boolean isVisible) {
        if (isVisible) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        }
    }

    public void setBottomSheetVisibility(boolean visibility) {
        if (visibility) {
            findViewById(R.id.player_bottom_sheet).setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.player_bottom_sheet).setVisibility(View.GONE);
        }
    }

    private void checkBottomSheetAfterStateChanged() {
        final Handler handler = new Handler();
        final Runnable runnable = () -> setBottomSheetInPeek(mainViewModel.isQueueLoaded());
        handler.postDelayed(runnable, 100);
    }

    public void collapseBottomSheetDelayed() {
        final Handler handler = new Handler();
        final Runnable runnable = () -> bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        handler.postDelayed(runnable, 100);
    }

    public void expandBottomSheet() {
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    public void setBottomSheetDraggableState(Boolean isDraggable) {
        bottomSheetBehavior.setDraggable(isDraggable);
    }

    private final BottomSheetBehavior.BottomSheetCallback bottomSheetCallback =
            new BottomSheetBehavior.BottomSheetCallback() {
                int navigationHeight;

                @Override
                public void onStateChanged(@NonNull View view, int state) {
                    PlayerBottomSheetFragment playerBottomSheetFragment = (PlayerBottomSheetFragment) getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");

                    switch (state) {
                        case BottomSheetBehavior.STATE_HIDDEN:
                            resetMusicSession();
                            break;
                        case BottomSheetBehavior.STATE_COLLAPSED:
                            if (playerBottomSheetFragment != null)
                                playerBottomSheetFragment.goBackToFirstPage();
                            break;
                        case BottomSheetBehavior.STATE_SETTLING:
                        case BottomSheetBehavior.STATE_EXPANDED:
                        case BottomSheetBehavior.STATE_DRAGGING:
                        case BottomSheetBehavior.STATE_HALF_EXPANDED:
                            break;
                    }
                }

                @Override
                public void onSlide(@NonNull View view, float slideOffset) {
                    animateBottomSheet(slideOffset);
                    if (!isLandscape) {
                        animateBottomNavigation(slideOffset, navigationHeight);
                    }
                }
            };

    private void animateBottomSheet(float slideOffset) {
        PlayerBottomSheetFragment playerBottomSheetFragment = (PlayerBottomSheetFragment) getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");
        if (playerBottomSheetFragment != null) {
            float condensedSlideOffset = Math.max(0.0f, Math.min(0.2f, slideOffset - 0.2f)) / 0.2f;
            playerBottomSheetFragment.getPlayerHeader().setAlpha(1 - condensedSlideOffset);
            playerBottomSheetFragment.getPlayerHeader().setVisibility(condensedSlideOffset > 0.99 ? View.GONE : View.VISIBLE);
        }
    }

    private void animateBottomNavigation(float slideOffset, int navigationHeight) {
        if (slideOffset < 0) return;

        if (navigationHeight == 0) {
            navigationHeight = bind.bottomNavigation.getHeight();
        }

        float slideY = navigationHeight - navigationHeight * (1 - slideOffset);

        bind.bottomNavigation.setTranslationY(slideY);
    }

    private void initNavigation() {
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        navHostFragment = (NavHostFragment) fragmentManager.findFragmentById(R.id.nav_host_fragment);
        navController = Objects.requireNonNull(navHostFragment).getNavController();

        /*
         * In questo modo intercetto il cambio schermata tramite navbar e se il bottom sheet è aperto,
         * lo chiudo
         */
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED && (
                    destination.getId() == R.id.homeFragment ||
                            destination.getId() == R.id.libraryFragment ||
                            destination.getId() == R.id.downloadFragment)
            ) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }

            // Redirect to home fragment
            if (destination.getId() == R.id.landingFragment && isLoggedIn) {
                isLastPage = true;
                setBottomNavigationBarVisibility(true);
                if (prevFragmentTag != null) {
                    switchMainFragments(Integer.parseInt(prevFragmentTag));
                } else {
                    switchMainFragments(R.id.homeFragment);
                }
            } else {
                // Hide all fragments
                hideMainFragments();
                isLastPage = false;

                // Show NavHostFragment
                for (Fragment fragment : fragmentManager.getFragments()) {
                    if (fragment instanceof NavHostFragment) {
                        FragmentTransaction transaction = fragmentManager.beginTransaction();
                        transaction.show(fragment);
                        transaction.commit();
                    }
                }
            }
        });

        // NavigationUI.setupWithNavController(bottomNavigationView, navController);

        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            switchMainFragments(itemId);
            return true;
        });
    }

    private void hideMainFragments() {
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        for (Fragment fragment : fragmentManager.getFragments()) {
            Log.d(TAG, "FRAGMENT: " + fragment.getClass().getSimpleName());
            if (
                fragment instanceof HomeFragment ||
                fragment instanceof LibraryFragment ||
                fragment instanceof DownloadFragment ||
                fragment instanceof NavHostFragment
            ) {
                transaction.hide(fragment);
            }
        }
        Log.d(TAG, "FRAGMENT: ---------- ");
        transaction.commit();
    }

    private void switchMainFragments(int itemId) {
        // Init
        if (itemId == R.id.homeFragment) {
            if (homeFragment == null) homeFragment = new HomeFragment();
            selectedFragment = homeFragment;
        }
        else if (itemId == R.id.libraryFragment) {
            if (libraryFragment == null) libraryFragment = new LibraryFragment();
            selectedFragment = libraryFragment;
        }
        else if (itemId == R.id.downloadFragment) {
            if (downloadFragment == null) downloadFragment = new DownloadFragment();
            selectedFragment = downloadFragment;
        }

        // Show fragments
        prevFragmentTag = String.valueOf(itemId);
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        hideMainFragments();
        if (fragmentManager.findFragmentByTag(prevFragmentTag) != null) {
            transaction.show(Objects.requireNonNull(fragmentManager.findFragmentByTag(prevFragmentTag)));
        }
        else {
            transaction.add(R.id.nav_host_fragment, selectedFragment, prevFragmentTag);
        }
        transaction.addToBackStack(null);
        transaction.commit();
    }

    public void setBottomNavigationBarVisibility(boolean visibility) {
        if (visibility) {
            bottomNavigationView.setVisibility(View.VISIBLE);
        } else {
            bottomNavigationView.setVisibility(View.GONE);
        }
    }

    private void initService() {
        MediaManager.check(getMediaBrowserListenableFuture());

        getMediaBrowserListenableFuture().addListener(() -> {
            try {
                getMediaBrowserListenableFuture().get().addListener(new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        if (isPlaying && bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
                            setBottomSheetInPeek(true);
                        }
                    }
                });
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void goToLogin() {
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        setBottomNavigationBarVisibility(false);
        setBottomSheetVisibility(false);

        if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.landingFragment) {
            navController.navigate(R.id.action_landingFragment_to_loginFragment);
        } else if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.settingsFragment) {
            navController.navigate(R.id.action_settingsFragment_to_loginFragment);
        } else if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.homeFragment) {
            navController.navigate(R.id.action_homeFragment_to_loginFragment);
        }
    }

    private void goToHome() {
        bottomNavigationView.setVisibility(View.VISIBLE);

        if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.loginFragment) {
            navController.popBackStack(R.id.loginFragment, true);
            navController.navigate(R.id.landingFragment);
            // switchMainFragments(R.id.homeFragment);
        }
    }

    public void goFromLogin() {
        setBottomSheetInPeek(mainViewModel.isQueueLoaded());
        goToHome();
    }

    public void quit() {
        resetUserSession();
        resetMusicSession();
        resetViewModel();
        goToLogin();
    }

    private void resetUserSession() {
        Preferences.setServerId(null);
        Preferences.setSalt(null);
        Preferences.setToken(null);
        Preferences.setPassword(null);
        Preferences.setServer(null);
        Preferences.setLocalAddress(null);
        Preferences.setUser(null);

        // TODO Enter all settings to be reset
        Preferences.setOpenSubsonic(false);
        Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_100);
        Preferences.setSkipSilenceMode(false);
        Preferences.setDataSavingMode(false);
        Preferences.setStarredSyncEnabled(false);
    }

    private void resetMusicSession() {
        MediaManager.reset(getMediaBrowserListenableFuture());
    }

    private void hideMusicSession() {
        MediaManager.hide(getMediaBrowserListenableFuture());
    }

    private void resetViewModel() {
        this.getViewModelStore().clear();
    }

    // CONNECTION
    private void connectivityStatusReceiverManager(boolean isActive) {
        if (isActive) {
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(connectivityStatusBroadcastReceiver, filter);
        } else {
            unregisterReceiver(connectivityStatusBroadcastReceiver);
        }
    }

    private void pingServer() {
        if (Preferences.getToken() == null) return;

        if (Preferences.isInUseServerAddressLocal()) {
            mainViewModel.ping().observe(this, subsonicResponse -> {
                if (subsonicResponse == null) {
                    Preferences.setServerSwitchableTimer();
                    Preferences.switchInUseServerAddress();
                    App.refreshSubsonicClient();
                    pingServer();
                } else {
                    Preferences.setOpenSubsonic(subsonicResponse.getOpenSubsonic() != null && subsonicResponse.getOpenSubsonic());
                }
            });
        } else {
            if (Preferences.isServerSwitchable()) {
                Preferences.setServerSwitchableTimer();
                Preferences.switchInUseServerAddress();
                App.refreshSubsonicClient();
                pingServer();
            } else {
                mainViewModel.ping().observe(this, subsonicResponse -> {
                    if (subsonicResponse == null) {
                        if (Preferences.showServerUnreachableDialog()) {
                            ServerUnreachableDialog dialog = new ServerUnreachableDialog();
                            dialog.show(getSupportFragmentManager(), null);
                        }
                    } else {
                        Preferences.setOpenSubsonic(subsonicResponse.getOpenSubsonic() != null && subsonicResponse.getOpenSubsonic());
                    }
                });
            }
        }
    }

    private void getOpenSubsonicExtensions() {
        if (Preferences.getToken() != null) {
            mainViewModel.getOpenSubsonicExtensions().observe(this, openSubsonicExtensions -> {
                if (openSubsonicExtensions != null) {
                    Preferences.setOpenSubsonicExtensions(openSubsonicExtensions);
                }
            });
        }
    }

    private void checkTempoUpdate() {
        if (BuildConfig.FLAVOR.equals("tempo") && Preferences.showTempoUpdateDialog()) {
            mainViewModel.checkTempoUpdate().observe(this, latestRelease -> {
                if (latestRelease != null && UpdateUtil.showUpdateDialog(latestRelease)) {
                    GithubTempoUpdateDialog dialog = new GithubTempoUpdateDialog(latestRelease);
                    dialog.show(getSupportFragmentManager(), null);
                }
            });
        }
    }

    private void checkConnectionType() {
        if (Preferences.isWifiOnly()) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

            if (networkInfo != null && networkInfo.getType() != ConnectivityManager.TYPE_WIFI) {
                ConnectionAlertDialog dialog = new ConnectionAlertDialog();
                dialog.show(getSupportFragmentManager(), null);
            }
        }
    }
}
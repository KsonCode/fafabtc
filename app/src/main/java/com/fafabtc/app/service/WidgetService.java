package com.fafabtc.app.service;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import com.fafabtc.app.constants.Services;
import com.fafabtc.app.receiver.AssetsWidgetProvider;
import com.fafabtc.app.utils.ExecutorManager;
import com.fafabtc.app.utils.TickersAlarmUtils;
import com.fafabtc.app.utils.WidgetUtils;
import com.fafabtc.data.data.repo.AccountAssetsRepo;
import com.fafabtc.data.data.repo.AssetsStatisticsRepo;
import com.fafabtc.data.data.repo.DataRepo;
import com.fafabtc.data.global.AssetsStateRepository;
import com.fafabtc.data.model.entity.exchange.AccountAssets;
import com.fafabtc.data.model.vo.WidgetData;

import java.util.Date;

import javax.inject.Inject;

import dagger.android.DaggerService;
import io.reactivex.CompletableObserver;
import io.reactivex.SingleObserver;
import io.reactivex.SingleSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

public class WidgetService extends DaggerService {

    private static final int MSG_MANUL_UPDATE_TICKERS = 1;

    @Inject
    DataRepo dataRepo;

    @Inject
    AccountAssetsRepo accountAssetsRepo;

    @Inject
    AssetsStatisticsRepo assetsStatisticsRepo;

    @Inject
    AssetsStateRepository assetsStateRepository;

    public static final int MIN_UPDATE_INTERVAL = 60 * 1000;

    private boolean isUpdating = false;
    private boolean isManulUpdateQueued = false;

    private Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            updateTickers(WidgetService.this);
            isManulUpdateQueued = false;
            return true;
        }
    });

    public WidgetService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (WidgetUtils.isWidgetAvailable(this)) {
            TickersAlarmUtils.cancelUpdate(this);
            TickersAlarmUtils.scheduleUpdate(this);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case Services.Actions.ACTION_UPDATE_TICKERS:
                    updateTickers(this);
                    break;
                case Services.Actions.ACTION_MANUL_UPDATE_TICKERS:
                    AssetsWidgetProvider.showLoadingProgress(this);
                    queueUpdateTickers();
                    break;
                case Services.Actions.ACTION_UPDATE_WIDGET:
                    updateWidgetData();
                    break;
            }
        }
        return START_STICKY;
    }

    private void queueUpdateTickers() {
        if (isManulUpdateQueued) return;
        isManulUpdateQueued = true;
        handler.removeMessages(MSG_MANUL_UPDATE_TICKERS);
        handler.sendEmptyMessageDelayed(MSG_MANUL_UPDATE_TICKERS, getDelay());
    }

    private void updateTickers(final Context context) {
        if (getDelay() > 0) return;
        if (isUpdating) return;
        isUpdating = true;
        dataRepo.refreshTickers()
                .subscribeOn(Schedulers.from(ExecutorManager.getIO()))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onComplete() {
                        isUpdating = false;
                        Timber.d("onComplete");
                    }

                    @Override
                    public void onError(Throwable e) {
                        isUpdating = false;
                        Timber.e(e);
                        AssetsWidgetProvider.hideLoadingProgress(WidgetService.this);
                    }
                });
    }

    long initialDelay = 0;
    private long getDelay() {
        initialDelay = 0;
        assetsStateRepository.getUpdateTime()
                .subscribe(new SingleObserver<Date>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onSuccess(Date date) {
                        long elapsed = System.currentTimeMillis() - date.getTime();
                        initialDelay = elapsed > MIN_UPDATE_INTERVAL ? 0 : MIN_UPDATE_INTERVAL - elapsed;
                    }

                    @Override
                    public void onError(Throwable e) {

                    }
                });
        return initialDelay;
    }

    private void updateWidgetData() {
        final WidgetData widgetData = new WidgetData();
        accountAssetsRepo.getCurrent()
                .flatMap(new Function<AccountAssets, SingleSource<Double>>() {
                    @Override
                    public SingleSource<Double> apply(AccountAssets accountAssets) throws Exception {
                        widgetData.setAccountAssets(accountAssets);
                        return assetsStatisticsRepo.getAccountTotalVolume(accountAssets.getUuid());
                    }
                })
                .zipWith(assetsStateRepository.getFormatedUpdateTime(), new BiFunction<Double, String, WidgetData>() {
                    @Override
                    public WidgetData apply(Double aDouble, String s) throws Exception {
                        widgetData.setVolume(aDouble);
                        widgetData.setUpdateTime(s);
                        return widgetData;
                    }
                })
                .subscribeOn(Schedulers.from(ExecutorManager.getNOW()))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleObserver<WidgetData>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onSuccess(WidgetData data) {
                        AssetsWidgetProvider.update(WidgetService.this, data);
                    }

                    @Override
                    public void onError(Throwable e) {
                        Timber.e(e);
                    }
                });
    }
}

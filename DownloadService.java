package com.handsmap.util.download;

import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;

import com.handsmap.nsstour.IDownloadService;
import com.handsmap.nsstour.manager.download.DownloadManager;


/**
 * 不需要直接手动启动这个Service 所以不需要对外公开这个Service
 */
public class DownloadService extends Service
{
    private DownloadControl mControl;
    //下载广播消息接收器
    private DownloadManager.DownloadReceiver downloadReceiver;

    @Override
    public IBinder onBind(Intent intent)
    {
        return new ServiceStub();
    }

    @Override
    public void onCreate()
    {
        super.onCreate();
        mControl = new DownloadControl(this);
        DownloadManager manager = DownloadManager.getInstance(getApplicationContext());
        downloadReceiver = manager.new DownloadReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(DownloadControl.RECEIVER_ACTION);
        registerReceiver(downloadReceiver, filter); //注册广播
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        //服务停止时注销广播
        unregisterReceiver(downloadReceiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        return super.onStartCommand(intent, flags, startId);
    }

    private class ServiceStub extends IDownloadService.Stub
    {

        @Override
        public void addTask(String url) throws RemoteException
        {
            if (!TextUtils.isEmpty(url))
            {
                mControl.addTask(url);
            }
        }

        @Override
        public void pauseTask(String url) throws RemoteException
        {
            if (!TextUtils.isEmpty(url))
            {
                mControl.pauseTask(url);
            }
        }

        @Override
        public void deleteTask(String url) throws RemoteException
        {
            if (!TextUtils.isEmpty(url))
            {
                mControl.deleteTask(url);
            }
        }

        @Override
        public void continueTask(String url) throws RemoteException
        {
            if (!TextUtils.isEmpty(url))
            {
                mControl.continueTask(url);
            }
        }
    }
}

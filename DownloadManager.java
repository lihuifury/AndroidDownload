package com.handsmap.nsstour.manager.download;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import com.handsmap.nsstour.IDownloadService;
import com.handsmap.nsstour.manager.BaseManager;
import com.handsmap.nsstour.manager.DataAccessManager;
import com.handsmap.util.Logger;
import com.handsmap.util.download.DownloadControl;
import com.handsmap.util.download.DownloadIntents;

/**
 * 文件下载控制类
 *
 * @author DaHui
 */
public class DownloadManager extends BaseManager
{

    private final String TAG = DownloadManager.class.getSimpleName();
    private Context mContext;
    private ServiceConnection mConn;
    //下载服务
    private IDownloadService mService;
    private static DownloadManager mManager;
    private DownloadListener downloadListener;

    private Intent intent;

    /**
     * 单一实例
     *
     * @param context
     * @return
     */
    public static DownloadManager getInstance(Context context)
    {
        if (mManager == null)
        {
            mManager = new DownloadManager(context);
        }
        return mManager;
    }

    /**
     * 私有构造函数，保证单一实例
     *
     * @param context
     */
    private DownloadManager(Context context)
    {
        this.mContext = context;
        initConn();
        downloadListener = (DownloadListener) context;
        intent = new Intent(DownloadControl.SERVICE_ACTION);
    }

    /**
     * 初始化服务连接，启动服务和绑定服务，StartService是因为需要一直后台运行，后台文件下载
     * 绑定服务是为了拿到操作对象，记得解除绑定和停止服务
     */
    private void initConn()
    {
        //启动服务，记得stop服务，也可以使用IntentService
        mContext.startService(intent);
        mConn = new ServiceConnection()
        {

            @Override
            public void onServiceDisconnected(ComponentName name)
            {
                //服务断开
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service)
            {
                //服务连接上
                mService = IDownloadService.Stub.asInterface(service);
            }
        };
        mContext.bindService(intent, mConn, Context.BIND_AUTO_CREATE);
    }

    /**
     * 去掉绑定服务
     */
    public void disConnectService()
    {
        mContext.unbindService(mConn);
    }

    /**
     * 停止下载服务
     */
    public void stopService()
    {
        mContext.stopService(intent);
    }

    /**
     * 添加任务
     *
     * @param url
     */
    public void addTask(String url)
    {
        if (mService != null)
        {
            try
            {
                mService.addTask(url);
            } catch (RemoteException e)
            {
                Logger.e(TAG, e.getMessage());
            }
        }
    }

    /**
     * 暂停任务
     *
     * @param url
     */
    public void pauseTask(String url)
    {
        if (mService != null)
        {
            try
            {
                mService.pauseTask(url);
            } catch (RemoteException e)
            {
                Logger.e(TAG, e.getMessage());
            }
        }
    }

    /**
     * 删除任务
     *
     * @param url
     */
    public void deleteTask(String url)
    {
        if (mService != null)
        {
            try
            {
                mService.deleteTask(url);
            } catch (RemoteException e)
            {
                Logger.e(TAG, e.getMessage());
            }
        }
    }

    /**
     * 继续任务
     *
     * @param url
     */
    public void continueTask(String url)
    {
        if (mService != null)
        {
            try
            {
                mService.continueTask(url);
            } catch (RemoteException e)
            {
                Logger.e(TAG, e.getMessage());
            }
        }
    }

    /**
     * 获取任务的状态
     *
     * @param url URL
     * @return
     */
    public int getStatus(String url)
    {
        return DataAccessManager.getInstance(mContext).getDownloadStatus(url);
    }

    /**
     * 下载广播接收器
     * 在Service中去注册广播，保证接收后台消息，Service停止时注销广播
     */
    public class DownloadReceiver extends BroadcastReceiver
    {

        public DownloadReceiver()
        {
        }

        @Override
        public void onReceive(Context context, Intent intent)
        {
            if (intent != null && intent.getAction().equals(DownloadControl.RECEIVER_ACTION))
            {
                String url = intent.getStringExtra(DownloadIntents.URL);
                switch (intent.getIntExtra(DownloadIntents.TYPE, -1))
                {
                    case DownloadIntents.Types.WAIT:
                        //下载之前的等待
                        downloadListener.downLoadWait(url);
                        break;
                    case DownloadIntents.Types.PROCESS:
                        //正在下载，更新下载进度
                        String progress = intent.getStringExtra(DownloadIntents.PROCESS_PROGRESS);
                        downloadListener.updateProgress(url, progress);
                        break;
                    case DownloadIntents.Types.COMPLETE:
                        //下载完成
                        downloadListener.downLoadComplete(url);
                        break;
                    case DownloadIntents.Types.ERROR:
                        //下载错误
                        String errorMsg = intent.getStringExtra(DownloadIntents.ERROR_INFO);
                        downloadListener.downLoadError(url, errorMsg);
                        break;
                }
            }
        }
    }

    @Override
    public void recycleManager()
    {
        downloadListener = null;
        mManager = null;
        //解除绑定服务
        disConnectService();
        super.recycleManager();
    }
}

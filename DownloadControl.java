package com.handsmap.util.download;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.handsmap.nsstour.manager.DataAccessManager;
import com.handsmap.util.Logger;
import com.handsmap.util.common.FileInfoUtils;
import com.handsmap.util.common.StringUtils;
import com.handsmap.util.extend.app.ToastUtil;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * 下载核心控制器
 *
 * @anthor DaHui，所有的下载逻辑控制都在这里，Service调用该类的方法进行下载
 */
public class DownloadControl extends Thread
{

    private static final String TAG = DownloadControl.class.getSimpleName();
    private static final int MAX_TASK_COUNT = 100;
    private static final int MAX_DOWNLOAD_THREAD_COUNT = 10;
    public static final String SERVICE_ACTION = "com.handsmap.nsstour.download.DownloadService";
    public static final String RECEIVER_ACTION = "com.handsmap.nsstour.downloader.receiver";
    //还没有下载 初始默认状态
    public static final int STATUS_DEFAULT = 0;//这种状态要注意加一个判断：是否已经下载完成并安装完成
    //正在下载
    public static final int STATUS_DOWNLOADING = 1;
    //下载完成
    public static final int STATUS_COMPLETE = 2;
    ///暂停下载
    public static final int STATUS_PAUSE = 3;
    //文件已存在
    public static final String ERROR_FILE_EXIST = "100";
    //URL不正确
    public static final String ERROR_URL = "101";
    //存储错误
    public static final String ERROR_NOMEMORY = "102";
    //下载过程中网络断开或者超时 该异常发生时下载终端需要用户点击下载以继续下载
    public static final String ERROR_DOWNLOAD_INTERRUPT = "103";
    private Context mContext;
    //等待下载的下载队列
    private TaskQueue mTaskQueue;
    //正在下载的任务
    private List<DownloadTask> mDownloadingTasks;
    //已经暂停的任务
    private List<DownloadTask> mPausedTasks;
    private boolean isRunning = false;
    private DataAccessManager dataAccessManager;

    public DownloadControl(Context context)
    {
        mContext = context;
        dataAccessManager = DataAccessManager.getInstance(context);
        mTaskQueue = new TaskQueue();
        mDownloadingTasks = new ArrayList<DownloadTask>();
        mPausedTasks = new ArrayList<DownloadTask>();
        try
        {
            FileInfoUtils.mkdir();
        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public synchronized void start()
    {
        super.start();
    }

    @Override
    public void run()
    {
        super.run();
        while (isRunning)
        {
            DownloadTask task = mTaskQueue.poll();
            mDownloadingTasks.add(task);
            task.execute();
        }
    }

    private DownloadTask downloadTask(String url) throws MalformedURLException
    {
        DownloadTask.DownloadTaskListener listener = new DownloadTask.DownloadTaskListener()
        {

            @Override
            public void updateProgress(DownloadTask task)
            {
                Intent updateIntent = new Intent(RECEIVER_ACTION);
                updateIntent.putExtra(DownloadIntents.TYPE, DownloadIntents.Types.PROCESS);
                long percent = task.getDownloadPercent();
                dataAccessManager.updateDownloadCurrentSize(task.getUrl(), task.getDownloadSize());
                updateIntent.putExtra(DownloadIntents.PROCESS_PROGRESS, String.valueOf(percent));
                updateIntent.putExtra(DownloadIntents.URL, task.getUrl());
                mContext.sendBroadcast(updateIntent);
            }

            @Override
            public void finishDownload(DownloadTask task)
            {
                completeTask(task, DownloadIntents.Types.COMPLETE);
            }

            @Override
            public void errorDownload(DownloadTask task, Throwable error)
            {
                errorTask(task, error);
            }
        };
        return new DownloadTask(mContext, url, FileInfoUtils.FILE_ROOT, listener);
    }

    /**
     * 添加任务
     *
     * @param url
     */
    public void addTask(String url)
    {
        if (!FileInfoUtils.isSDCardPresent())
        {
            ToastUtil.showToastShort(mContext, "未发现SD卡");
            return;
        }

        if (!FileInfoUtils.isSdCardWrittenable())
        {
            ToastUtil.showToastShort(mContext, "SD卡不能读写");
            return;
        }

        if (getTotalTaskCount() >= MAX_TASK_COUNT)
        {
            ToastUtil.showToastShort(mContext, "任务列表已满");
            return;
        }
        try
        {
            addTask(downloadTask(url));
        } catch (MalformedURLException e)
        {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    /**
     * 添加任务
     *
     * @param task 下载任务
     */
    private void addTask(DownloadTask task)
    {
        waitTask(task);
        mTaskQueue.offer(task);

        if (!this.isAlive())
        {
            isRunning = true;
            this.start();
        }
    }

    /**
     * 暂停任务
     *
     * @param url
     */
    public void pauseTask(String url)
    {
        for (int i = 0; i < mDownloadingTasks.size(); i++)
        {
            DownloadTask task = mDownloadingTasks.get(i);
            if (task != null && task.getUrl().equals(url))
            {
                pauseTask(task);
                break;
            }
        }
    }

    /**
     * 暂停任务
     *
     * @param task
     */
    private void pauseTask(DownloadTask task)
    {
        if (task != null)
        {
            task.pause();
            String url = task.getUrl();
            try
            {
                mDownloadingTasks.remove(task);
                task = downloadTask(url);
                mPausedTasks.add(task);
            } catch (MalformedURLException e)
            {
                Logger.e(TAG, e.getMessage());
            }
        }
    }

    /**
     * 删除下载任务
     *
     * @param url
     */
    public void deleteTask(String url)
    {

        DownloadTask task;
        // 如果是正在下载的任务删除了
        for (int i = 0; i < mDownloadingTasks.size(); i++)
        {
            task = mDownloadingTasks.get(i);
            if (task != null && task.getUrl().equals(url))
            {
                File file = new File(FileInfoUtils.FILE_ROOT + StringUtils.getFileNameFromUrl(task.getUrl()));
                if (file.exists())
                {
                    file.delete();
                }
                task.delete();
                completeTask(task, DownloadIntents.Types.DELETE);
                break;
            }
        }
        // 如果是待下载的任务删除了
        for (int i = 0; i < mTaskQueue.size(); i++)
        {
            task = mTaskQueue.get(i);
            if (task != null && task.getUrl().equals(url))
            {
                mTaskQueue.remove(task);
                break;
            }
        }
        // 如果是暂停的任务删除了
        for (int i = 0; i < mPausedTasks.size(); i++)
        {
            task = mPausedTasks.get(i);
            if (task != null && task.getUrl().equals(url))
            {
                mPausedTasks.remove(task);
                break;
            }
        }
    }

    /**
     * addTask到真正开始下载有个等待时间
     */
    private void waitTask(DownloadTask task)
    {
        Intent nofityIntent = new Intent(RECEIVER_ACTION);
        nofityIntent.putExtra(DownloadIntents.TYPE, DownloadIntents.Types.WAIT);
        nofityIntent.putExtra(DownloadIntents.URL, task.getUrl());
        mContext.sendBroadcast(nofityIntent);
    }

    private void completeTask(DownloadTask task, int type)
    {
        if (mDownloadingTasks.contains(task))
        {
            mDownloadingTasks.remove(task);
            Intent nofityIntent = new Intent(RECEIVER_ACTION);
            nofityIntent.putExtra(DownloadIntents.TYPE, type);
            nofityIntent.putExtra(DownloadIntents.URL, task.getUrl());
            mContext.sendBroadcast(nofityIntent);
        }
    }

    private void errorTask(DownloadTask task, Throwable error)
    {
        if (mDownloadingTasks.contains(task))
        {
            mDownloadingTasks.remove(task);
            Intent errorIntent = new Intent(RECEIVER_ACTION);
            errorIntent.putExtra(DownloadIntents.TYPE, DownloadIntents.Types.ERROR);
            if (error != null)
            {
                errorIntent.putExtra(DownloadIntents.ERROR_INFO, error.getMessage());
            }
            errorIntent.putExtra(DownloadIntents.URL, task.getUrl());
            mContext.sendBroadcast(errorIntent);
        }
    }

    public void continueTask(String url)
    {
        for (int i = 0, length = mPausedTasks.size(); i < length; i++)
        {
            DownloadTask task = mPausedTasks.get(i);
            if (task != null && task.getUrl().equals(url))
            {
                continueTask(task);
                break;
            }

        }
    }

    private void continueTask(DownloadTask task)
    {
        if (task != null)
        {
            mPausedTasks.remove(task);
            mTaskQueue.offer(task);
        }
    }

    private int getTotalTaskCount()
    {
        return mTaskQueue.size() + mDownloadingTasks.size() + mPausedTasks.size();
    }

    class TaskQueue
    {

        private Queue<DownloadTask> taskQueue;

        public TaskQueue()
        {

            taskQueue = new LinkedList<>();
        }

        public void offer(DownloadTask task)
        {

            taskQueue.offer(task);
        }

        public DownloadTask poll()
        {
            DownloadTask task;
            while (mDownloadingTasks.size() >= MAX_DOWNLOAD_THREAD_COUNT || (task = taskQueue.poll()) == null)
            {
                try
                {
                    Thread.sleep(1000); // sleep
                } catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
            return task;
        }

        public DownloadTask get(int position)
        {

            if (position >= size())
            {
                return null;
            }
            return ((LinkedList<DownloadTask>) taskQueue).get(position);
        }

        public int size()
        {

            return taskQueue.size();
        }

        public boolean remove(int position)
        {

            return taskQueue.remove(get(position));
        }

        public boolean remove(DownloadTask task)
        {

            return taskQueue.remove(task);
        }
    }

}

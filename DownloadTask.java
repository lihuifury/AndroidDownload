package com.handsmap.util.download;

import android.accounts.NetworkErrorException;
import android.content.Context;
import android.net.http.AndroidHttpClient;
import android.os.AsyncTask;

import com.handsmap.exception.DownloadException;
import com.handsmap.nsstour.manager.DataAccessManager;
import com.handsmap.nsstour.model.db.TB_DownloadInfo;
import com.handsmap.util.common.FileInfoUtils;
import com.handsmap.util.netstate.NetWorkUtil;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * 下载任务，使用异步任务进行下载操作
 *
 * @author DaHui
 */
public class DownloadTask extends AsyncTask<Void, Integer, Long>
{

    public interface DownloadTaskListener
    {
        public void updateProgress(DownloadTask task);

        public void finishDownload(DownloadTask task);

        public void errorDownload(DownloadTask task, Throwable error);
    }

    private static final String TAG = DownloadTask.class.getSimpleName();
    private static final String TEMP_SUFFIX = ".download";
    private static final int BUFFER_SIZE = 8 * 1024;

    private DownloadTaskListener mListener;
    private String mUrl;
    private Context mContext;
    private File mFile;
    private File mTempFile;
    //文件大小
    private long mTotalSize;
    //之前已经没有下载完的文件大小
    private long mPreviousFileSize;
    //下载的大小
    private long mDownloadSize;
    //下载百分比
    private long mDownloadPercent;
    //下载速度
    private long mDownloadSpeed;
    //开始下载的时间
    private long mStartTime;
    private boolean mInterrupt = false;
    private Throwable mError = null;

    private AndroidHttpClient mHttpClient;
    private DataAccessManager dataAccessManager;

    private final class ProgressReportingRandomAccessFile extends RandomAccessFile
    {

        private int progress = 0;

        public ProgressReportingRandomAccessFile(File file, String mode) throws FileNotFoundException
        {
            super(file, mode);
        }

        @Override
        public void write(byte[] buffer, int byteOffset, int byteCount) throws IOException
        {
            super.write(buffer, byteOffset, byteCount);

            progress += byteCount;
            publishProgress(progress);
        }
    }

    public DownloadTask(Context context, String url, String savedPath, DownloadTaskListener l) throws MalformedURLException
    {
        mContext = context;
        mUrl = url;
        mListener = l;

        URL u = new URL(url);
        String name = (new File(u.getFile())).getName();
        mFile = new File(savedPath, name);
        mTempFile = new File(savedPath, name + TEMP_SUFFIX);

        dataAccessManager = DataAccessManager.getInstance(context);
        TB_DownloadInfo d = new TB_DownloadInfo();
        d.setUrl(url);
        d.setName(name);
        d.setSavePath(savedPath);
        dataAccessManager.downInfoInsert(d);
    }

    @Override
    protected void onPreExecute()
    {
        mStartTime = System.currentTimeMillis();
    }

    @Override
    protected Long doInBackground(Void... params)
    {

        long result = -1;
        try
        {
            result = download();
        } catch (NetworkErrorException | DownloadException | IOException e)
        {
            mError = e;
        }
        return result;
    }

    @Override
    protected void onPostExecute(Long result)
    {
        if (result == -1 || mInterrupt || mError != null)
        {
            // 下载过程中遇到错误就重置下载状态
            dataAccessManager.updateDownloadStatus(mUrl, DownloadControl.STATUS_PAUSE);
            if (mListener != null)
            {
                mListener.errorDownload(this, mError);
            }
            return;
        }
        /*
         * finish download
		 */
        mTempFile.renameTo(mFile);

        // 下载完成更新下载状态为下载完成状态
        dataAccessManager.updateDownloadStatus(mUrl, DownloadControl.STATUS_COMPLETE);
        if (mListener != null)
        {
            mListener.finishDownload(this);
        }
    }

    public void pause()
    {
        onCancelled();
        dataAccessManager.updateDownloadStatus(mUrl, DownloadControl.STATUS_PAUSE);
    }

    public void delete()
    {
        onCancelled();
        dataAccessManager.deleteDownloadInfo(mUrl);
    }

    @Override
    protected void onCancelled()
    {
        super.onCancelled();
        mInterrupt = true;
    }

    @Override
    protected void onProgressUpdate(Integer... values)
    {
        if (values.length > 1)
        {
            // 下载开始后会走到这里
            mTotalSize = values[1];
            // 避免暂停后然后继续下载会短暂的出现0%的情况需要计算下载百分比
            mDownloadPercent = (mDownloadSize + mPreviousFileSize) * 100 /
                    mTotalSize;
            if (mListener != null)
            {
                mListener.updateProgress(this);
            }
            dataAccessManager.updateDownloadStatus(mUrl, DownloadControl.STATUS_DOWNLOADING);
            dataAccessManager.updateDownloadTotalSize(mUrl, mTotalSize);
        } else
        {
            mDownloadSize = values[0];
            long totalTime = System.currentTimeMillis() - mStartTime;
            long tempSize = mDownloadSize + mPreviousFileSize;
            // mDao.updateCurrentSizeByUrl(mUrl, tempSize);

            mDownloadSpeed = mDownloadSize / totalTime;// kbps

            long temp = tempSize * 100 / mTotalSize;
            if (mDownloadPercent != temp)
            {
                mDownloadPercent = temp;
                if (mListener != null)
                {
                    mListener.updateProgress(this);
                }
            }
        }
    }

    private long download() throws NetworkErrorException, IOException, DownloadException
    {

		/*
         * check net work
		 */
        if (!NetWorkUtil.isNetworkAvailable(mContext))
        {
            throw new NetworkErrorException();
        }

		/*
         * check file length
		 */
        mHttpClient = AndroidHttpClient.newInstance(TAG);
        HttpGet httpGet = new HttpGet(mUrl);
        HttpResponse response = mHttpClient.execute(httpGet);
        mTotalSize = response.getEntity().getContentLength();

        if (mTotalSize < 1024)
        {
            throw new DownloadException(DownloadControl.ERROR_URL);
        }

        if (mFile.exists() && mFile.length() == mTotalSize)
        {
            throw new DownloadException(DownloadControl.ERROR_FILE_EXIST);
        } else if (mTempFile.exists() && mTempFile.length() > 0)
        {
            // 已经下载过了，断点下载
            mPreviousFileSize = mTempFile.length() - 1;
            httpGet.addHeader("Range", "bytes=" + mPreviousFileSize + "-");
            mHttpClient.close();
            mHttpClient = AndroidHttpClient.newInstance(TAG);
            response = mHttpClient.execute(httpGet);
        }

		/*
         * check memory
		 */
        long storage = FileInfoUtils.getAvailableStorage();
        if (mTotalSize - mTempFile.length() > storage)
        {
            throw new DownloadException(DownloadControl.ERROR_NOMEMORY);
        }
        /*
         * start download
		 */
        RandomAccessFile accessFile = new ProgressReportingRandomAccessFile(mTempFile, "rw");
        // 提交当前下载文件大小
        publishProgress(0, (int) mTotalSize);
        InputStream inputStream = response.getEntity().getContent();

        int bytesCopied = copy(inputStream, accessFile);

        if ((mPreviousFileSize + bytesCopied) != mTotalSize && mTotalSize != 0 && !mInterrupt)
        {
            throw new DownloadException(DownloadControl.ERROR_DOWNLOAD_INTERRUPT);
        }
        return bytesCopied;
    }

    private int copy(InputStream inputStream, RandomAccessFile accessFile) throws IOException
    {

        if (inputStream == null || accessFile == null)
        {
            return -1;
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        BufferedInputStream bis = new BufferedInputStream(inputStream, BUFFER_SIZE);

        int totalCount = 0, readCount = 0;
        try
        {
            accessFile.seek(mPreviousFileSize);

            while (!mInterrupt)
            {
                readCount = inputStream.read(buffer, 0, BUFFER_SIZE);
                if (readCount == -1)
                {
                    break;
                }
                accessFile.write(buffer, 0, readCount);
                totalCount += readCount;
            }
        } finally
        {
            mHttpClient.close();
            mHttpClient = null;
            accessFile.close();
            inputStream.close();
            bis.close();
        }

        return totalCount;
    }

    public String getUrl()
    {
        return mUrl;
    }

    public long getDownloadPercent()
    {
        return mDownloadPercent;
    }

    public long getDownloadSpeed()
    {
        return mDownloadSpeed;
    }

    public long getDownloadSize()
    {
        return mDownloadSize + mPreviousFileSize;
    }

}

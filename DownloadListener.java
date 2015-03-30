package com.handsmap.nsstour.manager.download;

/**
 * Created by DaHui on 2015/2/3.
 * <p/>
 * 下载接口
 */
public interface DownloadListener
{
    //下载之前的等待
    public void downLoadWait(String url);

    //更新进度
    public void updateProgress(String url, String progress);

    //下载完成
    public void downLoadComplete(String url);

    //下载错误
    public void downLoadError(String url, String errorMsg);
}

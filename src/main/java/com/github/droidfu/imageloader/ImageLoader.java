/* Copyright (c) 2009 Matthias Kaeppler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.droidfu.imageloader;

import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.ImageView;

import com.github.droidfu.adapters.WebGalleryAdapter;
import com.github.droidfu.cachefu.ImageCache;
import com.github.droidfu.widgets.WebImageView;

/**
 * Realizes an background image loader backed by a two-level FIFO cache. If the
 * image to be loaded is present in the cache, it is set immediately on the
 * given view. Otherwise, a thread from a thread pool will be used to download
 * the image in the background and set the image on the view as soon as it
 * completes.
 * 
 * @author Matthias Kaeppler
 */
public class ImageLoader implements Runnable {

    private static ThreadPoolExecutor executor;

    private static ImageCache imageCache;

    private static final int DEFAULT_POOL_SIZE = 2;

    // expire images after a day
    private static final int DEFAULT_TTL_MINUTES = 24 * 60;

    public static final int HANDLER_MESSAGE_ID = 0;

    public static final String BITMAP_EXTRA = "droidfu:extra_bitmap";
    public static final String IMAGE_URL_EXTRA = "droidfu:extra_image_url";

    private static final int NO_POSITION = -1;

    private static int numAttempts = 3;

    /**
     * @param numThreads
     *        the maximum number of threads that will be started to download
     *        images in parallel
     */
    public static void setThreadPoolSize(int numThreads) {
        executor.setMaximumPoolSize(numThreads);
    }

    /**
     * @param numAttempts
     *        how often the image loader should retry the image download if
     *        network connection fails
     */
    public static void setMaxDownloadAttempts(int numAttempts) {
        ImageLoader.numAttempts = numAttempts;
    }

    /**
     * This method must be called before any other method is invoked on this
     * class. Please note that when using ImageLoader as part of
     * {@link WebImageView} or {@link WebGalleryAdapter}, then there is no need
     * to call this method, since those classes will already do that for you.
     * This method is idempotent. You may call it multiple times without any
     * side effects.
     * 
     * @param context
     *        the current context
     */
    public static synchronized void initialize(Context context) {
        if (executor == null) {
            executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
        }
        if (imageCache == null) {
            imageCache = new ImageCache(25, DEFAULT_TTL_MINUTES, DEFAULT_POOL_SIZE);
            imageCache.enableDiskCache(context, ImageCache.DISK_CACHE_SDCARD);
        }
    }

    private String imageUrl;

    private Handler handler;

    private ImageLoader(String imageUrl, ImageView imageView) {
        this.imageUrl = imageUrl;
        this.handler = new ImageLoaderHandler(imageView);
    }

    private ImageLoader(String imageUrl, ImageLoaderHandler handler) {
        this.imageUrl = imageUrl;
        this.handler = handler;
    }

    public ImageLoader(String imageUrl, ImageView imageView, int position) {
        this.imageUrl = imageUrl;
        this.handler = new ImageLoaderHandler(imageView, position);
    }

    /**
     * Triggers the image loader for the given image and view. The image loading
     * will be performed concurrently to the UI main thread, using a fixed size
     * thread pool. The loaded image will be posted back to the given ImageView
     * upon completion.
     * 
     * @param imageUrl
     *        the URL of the image to download
     * @param imageView
     *        the ImageView which should be updated with the new image
     */
    public static void start(String imageUrl, ImageView imageView) {
        start(imageUrl, imageView, NO_POSITION);
    }

    /**
     * Triggers the image loader for the given image and view. The image loading
     * will be performed concurrently to the UI main thread, using a fixed size
     * thread pool. The loaded image will be posted back to the given ImageView
     * upon completion. This method is intended to be used in a ListAdapter (for
     * a ListView) after setting the list item's position to the ImageView using
     * <code>setTag(position)</code>. Since ListViews re-use views for
     * performance optimization, it is not guaranteed that when the image has
     * finished downloading, the target ImageView will still be used to render
     * the requested image. ImageLoaderHandler checks that the index originally
     * intended for a given image is the same as the last index set using
     * setTag(), and can prevent a flicker effect after many images are loaded
     * for the same ImageView.
     * 
     * @param imageUrl
     *        the URL of the image to download
     * @param imageView
     *        the ImageView which should be updated with the new image
     * @param position
     *        the position of the item within the adapter's data set.
     */
    public static void start(String imageUrl, ImageView imageView, int position) {
        ImageLoader loader;
        if (position == NO_POSITION) {
            loader = new ImageLoader(imageUrl, imageView);
        } else {
            loader = new ImageLoader(imageUrl, imageView, position);
        }
        doLoadImage(loader);
    }

    /**
     * Triggers the image loader for the given image and handler. The image
     * loading will be performed concurrently to the UI main thread, using a
     * fixed size thread pool. The loaded image will not be automatically posted
     * to an ImageView; instead, you can pass a custom
     * {@link ImageLoaderHandler} and handle the loaded image yourself (e.g.
     * cache it for later use).
     * 
     * @param imageUrl
     *        the URL of the image to download
     * @param handler
     *        the handler which is used to handle the downloaded image
     */
    public static void start(String imageUrl, ImageLoaderHandler handler) {
        ImageLoader loader = new ImageLoader(imageUrl, handler);
        doLoadImage(loader);
    }

    /**
     * Loads the target image either from the cache or by downloading it.
     * 
     * @param loader
     *        loader instance that will be used if a download is required.
     */
    private static void doLoadImage(ImageLoader loader) {
        String imageUrl = loader.imageUrl;

        synchronized (imageCache) {
            Bitmap image = imageCache.get(imageUrl);
            if (image == null) {
                // fetch the image in the background
                executor.execute(loader);
            } else {
                loader.notifyImageLoaded(imageUrl, image);
            }
        }
    }

    /**
     * Clears the 1st-level cache (in-memory cache). A good candidate for
     * calling in {@link android.app.Application#onLowMemory()}.
     */
    public static void clearCache() {
        synchronized (imageCache) {
            imageCache.clear();
        }
    }

    /**
     * Returns the image cache backing this image loader.
     * 
     * @return the {@link ImageCache}
     */
    public static ImageCache getImageCache() {
        return imageCache;
    }

    public void run() {
        Bitmap bitmap = null;
        int timesTried = 1;

        while (timesTried <= numAttempts) {
            try {
                URL url = new URL(imageUrl);
                bitmap = BitmapFactory.decodeStream(url.openStream());
                synchronized (imageCache) {
                    imageCache.put(imageUrl, bitmap);
                }
                break;
            } catch (Throwable e) {
                Log.w(ImageLoader.class.getSimpleName(), "download for " + imageUrl
                        + " failed (attempt " + timesTried + ")");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e1) {
                }

                timesTried++;
            }
        }

        if (bitmap != null) {
            notifyImageLoaded(imageUrl, bitmap);
        }
    }

    public void notifyImageLoaded(String url, Bitmap bitmap) {
        Message message = new Message();
        message.what = HANDLER_MESSAGE_ID;
        Bundle data = new Bundle();
        data.putString(IMAGE_URL_EXTRA, url);
        data.putParcelable(BITMAP_EXTRA, bitmap);
        message.setData(data);

        handler.sendMessage(message);
    }
}

/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.github.lzyzsd.circleprogress.DonutProgress;
import org.apache.http.StatusLine;
import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.account.RedditAccountManager;
import org.quantumbadger.redreader.cache.CacheManager;
import org.quantumbadger.redreader.cache.CacheRequest;
import org.quantumbadger.redreader.cache.RequestFailureType;
import org.quantumbadger.redreader.common.*;
import org.quantumbadger.redreader.image.GetAlbumInfoListener;
import org.quantumbadger.redreader.image.GetImageInfoListener;
import org.quantumbadger.redreader.image.GifDecoderThread;
import org.quantumbadger.redreader.image.ImgurAPI;
import org.quantumbadger.redreader.reddit.prepared.RedditPreparedPost;
import org.quantumbadger.redreader.reddit.things.RedditPost;
import org.quantumbadger.redreader.reddit.url.PostCommentListingURL;
import org.quantumbadger.redreader.views.GIFView;
import org.quantumbadger.redreader.views.RedditPostView;
import org.quantumbadger.redreader.views.bezelmenu.BezelSwipeOverlay;
import org.quantumbadger.redreader.views.bezelmenu.SideToolbarOverlay;
import org.quantumbadger.redreader.views.glview.RRGLSurfaceView;
import org.quantumbadger.redreader.views.imageview.ImageTileSource;
import org.quantumbadger.redreader.views.imageview.ImageTileSourceWholeBitmap;
import org.quantumbadger.redreader.views.imageview.ImageViewDisplayListManager;
import org.quantumbadger.redreader.views.liststatus.ErrorView;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

public class ImageViewActivity extends BaseActivity implements RedditPostView.PostSelectionListener, ImageViewDisplayListManager.Listener {

	private class HorizontalSwipeOverlay extends RelativeLayout {

		private final ImageView mIcon;
		private final DonutProgress mProgress;
		private int mCurrentIconResource = 0;

		public HorizontalSwipeOverlay(final Context context) {
			super(context);

			final View background = new View(context);
			final int backgroundDimensionsPx = General.dpToPixels(context, 200);
			background.setBackgroundColor(Color.argb(127, 0, 0, 0));
			addView(background);
			background.getLayoutParams().width = backgroundDimensionsPx;
			background.getLayoutParams().height = backgroundDimensionsPx;
			((RelativeLayout.LayoutParams)background.getLayoutParams()).addRule(RelativeLayout.CENTER_IN_PARENT);

			mIcon = new ImageView(context);
			mIcon.setImageResource(R.drawable.ic_action_forward_dark);
			mCurrentIconResource = R.drawable.ic_action_forward_dark;
			addView(mIcon);
			((RelativeLayout.LayoutParams)mIcon.getLayoutParams()).addRule(RelativeLayout.CENTER_IN_PARENT);

			mProgress = new DonutProgress(context);

			addView(mProgress);
			((RelativeLayout.LayoutParams)mProgress.getLayoutParams()).addRule(RelativeLayout.CENTER_IN_PARENT);
			final int progressDimensionsPx = General.dpToPixels(context, 150);
			mProgress.getLayoutParams().width = progressDimensionsPx;
			mProgress.getLayoutParams().height = progressDimensionsPx;

			mProgress.setFinishedStrokeColor(Color.RED);
			mProgress.setUnfinishedStrokeColor(Color.argb(127, 0, 0, 0));
			final int progressStrokeWidthPx = General.dpToPixels(context, 15);
			mProgress.setUnfinishedStrokeWidth(progressStrokeWidthPx);
			mProgress.setFinishedStrokeWidth(progressStrokeWidthPx);
			mProgress.setStartingDegree(-90);
			mProgress.initPainters();

			setVisibility(GONE);
		}

		private void setIconResource(final int resource) {
			if(resource != mCurrentIconResource) {
				mCurrentIconResource = resource;
				mIcon.setImageResource(resource);
			}
		}

		public void onSwipeUpdate(float px, float maxPx) {

			mProgress.setProgress(-(px / maxPx));

			if(Math.abs(px) > 20) {
				setVisibility(VISIBLE);
			}

			if(px < 0) {
				setIconResource(R.drawable.ic_action_forward_dark);
			} else {
				setIconResource(R.drawable.ic_action_back_dark);
			}
		}

		public void onSwipeEnd() {
			setVisibility(GONE);
		}
	}

	GLSurfaceView surfaceView;
	private ImageView imageView;
	private GifDecoderThread gifThread;

	private String mUrl;

	private boolean mIsPaused = true, mIsDestroyed = false;
	private CacheRequest mRequest;

	private boolean mHaveReverted = false;

	private ImageViewDisplayListManager mImageViewDisplayerManager;

	private HorizontalSwipeOverlay mSwipeOverlay;
	private boolean mSwipeCancelled;

	private RedditPost mPost;

	private ImgurAPI.AlbumInfo mAlbumInfo;
	private int mAlbumImageIndex;

	@Override
	protected void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);

		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		final boolean solidblack = PrefsUtility.appearance_solidblack(this, sharedPreferences);

		if(solidblack) getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

		final Intent intent = getIntent();

		mUrl = intent.getDataString();

		if(mUrl == null) {
			finish();
			return;
		}

		mPost = intent.getParcelableExtra("post");

		if(intent.hasExtra("album")) {
			ImgurAPI.getAlbumInfo(
					this,
					intent.getStringExtra("album"),
					Constants.Priority.IMAGE_VIEW,
					0,
					new GetAlbumInfoListener() {

						@Override
						public void onFailure(
								final RequestFailureType type,
								final Throwable t,
								final StatusLine status,
								final String readableMessage) {

							// Do nothing
						}

						@Override
						public void onSuccess(final ImgurAPI.AlbumInfo info) {
							AndroidApi.UI_THREAD_HANDLER.post(new Runnable() {
								@Override
								public void run() {
									mAlbumInfo = info;
									mAlbumImageIndex = intent.getIntExtra("albumImageIndex", 0);
								}
							});
						}
					}
			);
		}

		Log.i("ImageViewActivity", "Loading URL " + mUrl);

		final DonutProgress progressBar = new DonutProgress(this);
		progressBar.setIndeterminate(true);
		progressBar.setFinishedStrokeColor(Color.rgb(200, 200, 200));
		progressBar.setUnfinishedStrokeColor(Color.rgb(50, 50, 50));
		final int progressStrokeWidthPx = General.dpToPixels(this, 15);
		progressBar.setUnfinishedStrokeWidth(progressStrokeWidthPx);
		progressBar.setFinishedStrokeWidth(progressStrokeWidthPx);
		progressBar.setStartingDegree(-90);
		progressBar.initPainters();

		final RelativeLayout progressLayout = new RelativeLayout(this);
		progressLayout.addView(progressBar);
		final int progressDimensionsPx = General.dpToPixels(this, 150);
		progressBar.getLayoutParams().width = progressDimensionsPx;
		progressBar.getLayoutParams().height = progressDimensionsPx;
		((RelativeLayout.LayoutParams)progressBar.getLayoutParams()).addRule(RelativeLayout.CENTER_IN_PARENT);

		final FrameLayout layout = new FrameLayout(this);
		layout.addView(progressLayout);

		LinkHandler.getImageInfo(this, mUrl, Constants.Priority.IMAGE_VIEW, 0, new GetImageInfoListener() {

			@Override
			public void onFailure(final RequestFailureType type, final Throwable t, final StatusLine status, final String readableMessage) {
				revertToWeb();
			}

			@Override
			public void onSuccess(final ImgurAPI.ImageInfo info) {

				Log.i("ImageViewActivity", "Got image URL: " + info.urlOriginal);

				final URI uri = General.uriFromString(info.urlOriginal);

				if(uri == null) {
					revertToWeb();
					return;
				}

				CacheManager.getInstance(ImageViewActivity.this).makeRequest(
						mRequest = new CacheRequest(
								uri,
								RedditAccountManager.getAnon(),
								null,
								Constants.Priority.IMAGE_VIEW,
								0,
								CacheRequest.DownloadType.IF_NECESSARY,
								Constants.FileType.IMAGE,
								false,
								false,
								false,
								ImageViewActivity.this) {

							private void setMainView(View v) {

								layout.removeAllViews();
								layout.addView(v);

								mSwipeOverlay = new HorizontalSwipeOverlay(ImageViewActivity.this);
								layout.addView(mSwipeOverlay);

								v.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
								v.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
							}

							@Override
							protected void onCallbackException(Throwable t) {
								BugReportActivity.handleGlobalError(context.getApplicationContext(), new RRError(null, null, t));
							}

							@Override
							protected void onDownloadNecessary() {
								AndroidApi.UI_THREAD_HANDLER.post(new Runnable() {
									@Override
									public void run() {
										progressBar.setVisibility(View.VISIBLE);
										progressBar.setIndeterminate(true);
									}
								});
							}

							@Override
							protected void onDownloadStarted() {
							}

							@Override
							protected void onFailure(final RequestFailureType type, Throwable t, StatusLine status, final String readableMessage) {

								final RRError error = General.getGeneralErrorForFailure(context, type, t, status, url.toString());

								AndroidApi.UI_THREAD_HANDLER.post(new Runnable() {
									public void run() {
										// TODO handle properly
										mRequest = null;
										progressBar.setVisibility(View.GONE);
										layout.addView(new ErrorView(ImageViewActivity.this, error));
									}
								});
							}

							@Override
							protected void onProgress(final boolean authorizationInProgress, final long bytesRead, final long totalBytes) {
								AndroidApi.UI_THREAD_HANDLER.post(new Runnable() {
									@Override
									public void run() {
										progressBar.setVisibility(View.VISIBLE);
										progressBar.setIndeterminate(authorizationInProgress);
										progressBar.setProgress(((float)((1000 * bytesRead) / totalBytes)) / 1000);
									}
								});
							}

							@Override
							protected void onSuccess(final CacheManager.ReadableCacheFile cacheFile, long timestamp, UUID session, boolean fromCache, final String mimetype) {

								if(mimetype == null || (!Constants.Mime.isImage(mimetype) && !Constants.Mime.isVideo(mimetype))) {
									revertToWeb();
									return;
								}

								final InputStream cacheFileInputStream;
								try {
									cacheFileInputStream = cacheFile.getInputStream();
								} catch(IOException e) {
									notifyFailure(RequestFailureType.PARSE, e, null, "Could not read existing cached image.");
									return;
								}

								if(cacheFileInputStream == null) {
									notifyFailure(RequestFailureType.CACHE_MISS, null, null, "Could not find cached image");
									return;
								}

								if(Constants.Mime.isVideo(mimetype)) {

									AndroidApi.UI_THREAD_HANDLER.post(new Runnable() {
										public void run() {

											if(mIsDestroyed) return;
											mRequest = null;

											try {
												final RelativeLayout layout = new RelativeLayout(context);
												layout.setGravity(Gravity.CENTER);

												final VideoView videoView = new VideoView(ImageViewActivity.this);

												videoView.setVideoURI(cacheFile.getUri());
												videoView.setMediaController(new MediaController(context));

												layout.addView(videoView);
												setMainView(layout);

												videoView.requestFocus();

												layout.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
												layout.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
												videoView.setLayoutParams(new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

												videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
													@Override
													public void onPrepared(MediaPlayer mp) {
														mp.setLooping(true);
														videoView.start();
													}
												});

												videoView.setOnErrorListener(
														new MediaPlayer.OnErrorListener() {
															@Override
															public boolean onError(final MediaPlayer mediaPlayer, final int i, final int i1) {
																revertToWeb();
																return true;
															}
														});

												final View.OnTouchListener touchListener = new View.OnTouchListener() {
													@Override
													public boolean onTouch(final View view, final MotionEvent motionEvent) {
														finish();
														return true;
													}
												};

												videoView.setOnTouchListener(touchListener);
												layout.setOnTouchListener(touchListener);

											} catch(OutOfMemoryError e) {
												General.quickToast(context, R.string.imageview_oom);
												revertToWeb();

											} catch(Throwable e) {
												General.quickToast(context, R.string.imageview_invalid_video);
												revertToWeb();
											}
										}
									});

								} else if(Constants.Mime.isImageGif(mimetype)) {

									final PrefsUtility.GifViewMode gifViewMode = PrefsUtility.pref_behaviour_gifview_mode(context, sharedPreferences);

									if(gifViewMode == PrefsUtility.GifViewMode.INTERNAL_BROWSER) {
										revertToWeb();
										return;

									} else if(gifViewMode == PrefsUtility.GifViewMode.EXTERNAL_BROWSER) {
										AndroidApi.UI_THREAD_HANDLER.post(new Runnable() {
											@Override
											public void run() {
												LinkHandler.openWebBrowser(ImageViewActivity.this, Uri.parse(mUrl));
												finish();
											}
										});

										return;
									}

									if(AndroidApi.isIceCreamSandwichOrLater()
											&& gifViewMode == PrefsUtility.GifViewMode.INTERNAL_MOVIE) {

										AndroidApi.UI_THREAD_HANDLER.post(new Runnable() {
											public void run() {

												if(mIsDestroyed) return;
												mRequest = null;

												try {
													final GIFView gifView = new GIFView(ImageViewActivity.this, cacheFileInputStream);
													setMainView(gifView);
													gifView.setOnClickListener(new View.OnClickListener() {
														public void onClick(View v) {
															finish();
														}
													});

												} catch(OutOfMemoryError e) {
													General.quickToast(context, R.string.imageview_oom);
													revertToWeb();

												} catch(Throwable e) {
													General.quickToast(context, R.string.imageview_invalid_gif);
													revertToWeb();
												}
											}
										});

									} else {

										gifThread = new GifDecoderThread(cacheFileInputStream, new GifDecoderThread.OnGifLoadedListener() {

											public void onGifLoaded() {
												AndroidApi.UI_THREAD_HANDLER.post(new Runnable() {
													public void run() {

														if(mIsDestroyed) return;
														mRequest = null;

														imageView = new ImageView(context);
														imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
														setMainView(imageView);
														gifThread.setView(imageView);

														imageView.setOnClickListener(new View.OnClickListener() {
															public void onClick(View v) {
																finish();
															}
														});
													}
												});
											}

											public void onOutOfMemory() {
												General.quickToast(context, R.string.imageview_oom);
												revertToWeb();
											}

											public void onGifInvalid() {
												General.quickToast(context, R.string.imageview_invalid_gif);
												revertToWeb();
											}
										});

										gifThread.start();

									}

								} else {

									final ImageTileSource imageTileSource;
									try {

										final long bytes = cacheFile.getSize();
										final byte[] buf = new byte[(int)bytes];

										try {
											new DataInputStream(cacheFileInputStream).readFully(buf);
										} catch(IOException e) {
											throw new RuntimeException(e);
										}

										try {
											imageTileSource = new ImageTileSourceWholeBitmap(buf);

										} catch(Throwable t) {
											Log.e("ImageViewActivity", "Exception when creating ImageTileSource", t);
											General.quickToast(context, R.string.imageview_decode_failed);
											revertToWeb();
											return;
										}

									} catch(OutOfMemoryError e) {
										General.quickToast(context, R.string.imageview_oom);
										revertToWeb();
										return;
									}

									AndroidApi.UI_THREAD_HANDLER.post(new Runnable() {
										public void run() {

											if(mIsDestroyed) return;
											mRequest = null;
											mImageViewDisplayerManager = new ImageViewDisplayListManager(imageTileSource, ImageViewActivity.this);
											surfaceView = new RRGLSurfaceView(ImageViewActivity.this, mImageViewDisplayerManager);
											setMainView(surfaceView);

											surfaceView.setOnClickListener(new View.OnClickListener() {
												public void onClick(View v) {
													finish();
												}
											});

											if(mIsPaused) {
												surfaceView.onPause();
											} else {
												surfaceView.onResume();
											}
										}
									});
								}
							}
						});
			}

			@Override
			public void onNotAnImage() {
				revertToWeb();
			}
		});

		final RedditPreparedPost post = mPost == null ? null
				: new RedditPreparedPost(this, CacheManager.getInstance(this), 0, mPost, -1, false,
				false, false, false, RedditAccountManager.getInstance(this).getDefaultAccount(), false);

		final FrameLayout outerFrame = new FrameLayout(this);
		outerFrame.addView(layout);

		if(post != null) {

			final SideToolbarOverlay toolbarOverlay = new SideToolbarOverlay(this);

			final BezelSwipeOverlay bezelOverlay = new BezelSwipeOverlay(this, new BezelSwipeOverlay.BezelSwipeListener() {

				public boolean onSwipe(BezelSwipeOverlay.SwipeEdge edge) {

					toolbarOverlay.setContents(post.generateToolbar(ImageViewActivity.this, false, toolbarOverlay));
					toolbarOverlay.show(edge == BezelSwipeOverlay.SwipeEdge.LEFT ?
							SideToolbarOverlay.SideToolbarPosition.LEFT : SideToolbarOverlay.SideToolbarPosition.RIGHT);
					return true;
				}

				public boolean onTap() {

					if(toolbarOverlay.isShown()) {
						toolbarOverlay.hide();
						return true;
					}

					return false;
				}
			});

			outerFrame.addView(bezelOverlay);
			outerFrame.addView(toolbarOverlay);

			bezelOverlay.getLayoutParams().width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
			bezelOverlay.getLayoutParams().height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;

			toolbarOverlay.getLayoutParams().width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
			toolbarOverlay.getLayoutParams().height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;

		}

		setContentView(outerFrame);
	}

	public void onPostSelected(final RedditPreparedPost post) {
		LinkHandler.onLinkClicked(this, post.url, false, post.src);
	}

	public void onPostCommentsSelected(final RedditPreparedPost post) {
		LinkHandler.onLinkClicked(this, PostCommentListingURL.forPostId(post.idAlone).generateJsonUri().toString(), false);
	}

	@Override
	public void onBackPressed() {
		if(General.onBackPressed()) super.onBackPressed();
	}

	private void revertToWeb() {

		final Runnable r = new Runnable() {
			public void run() {
				if(!mHaveReverted) {
					mHaveReverted = true;
					LinkHandler.onLinkClicked(ImageViewActivity.this, mUrl, true);
					finish();
				}
			}
		};

		if(General.isThisUIThread()) {
			r.run();
		} else {
			AndroidApi.UI_THREAD_HANDLER.post(r);
		}
	}

	@Override
	public void onPause() {

		if(mIsPaused) throw new RuntimeException();

		mIsPaused = true;

		Log.i("DEBUG", "ImageViewActivity.onPause()");
		super.onPause();
		if(surfaceView != null) {
			Log.i("DEBUG", "surfaceView.onPause()");
			surfaceView.onPause();
		}
	}

	@Override
	public void onResume() {

		if(!mIsPaused) throw new RuntimeException();

		mIsPaused = false;

		Log.i("DEBUG", "ImageViewActivity.onResume()");
		super.onResume();
		if(surfaceView != null) {
			Log.i("DEBUG", "surfaceView.onResume()");
			surfaceView.onResume();
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		mIsDestroyed = true;
		if(mRequest != null) mRequest.cancel();
		if(gifThread != null) gifThread.stopPlaying();
	}

	@Override
	public void onSingleTap() {
		finish();
	}

	@Override
	public void onHorizontalSwipe(final float pixels) {

		if(mSwipeCancelled) return;

		final int swipeDistance = General.dpToPixels(this, 200);

		if(mSwipeOverlay != null && mAlbumInfo != null) {
			mSwipeOverlay.onSwipeUpdate(pixels, swipeDistance);

			if(pixels >= swipeDistance) {
				// Back

				mSwipeCancelled = true;
				if(mSwipeOverlay != null) {
					mSwipeOverlay.onSwipeEnd();
				}

				if(mAlbumImageIndex > 0) {

					LinkHandler.onLinkClicked(
							this,
							mAlbumInfo.images.get(mAlbumImageIndex - 1).urlOriginal,
							false,
							mPost,
							mAlbumInfo,
							mAlbumImageIndex - 1);

					finish();

				} else {
					General.quickToast(this, "Already at first image in album."); // TODO string
				}

			} else if(pixels <= -swipeDistance) {
				// Forwards

				mSwipeCancelled = true;
				if(mSwipeOverlay != null) {
					mSwipeOverlay.onSwipeEnd();
				}

				if(mAlbumImageIndex < mAlbumInfo.images.size() - 1) {

					LinkHandler.onLinkClicked(
							this,
							mAlbumInfo.images.get(mAlbumImageIndex + 1).urlOriginal,
							false,
							mPost,
							mAlbumInfo,
							mAlbumImageIndex + 1);

					finish();

				} else {
					General.quickToast(this, "Already at last image in album."); // TODO string
				}
			}
		}
	}

	@Override
	public void onHorizontalSwipeEnd() {

		mSwipeCancelled = false;

		if(mSwipeOverlay != null) {
			mSwipeOverlay.onSwipeEnd();
		}
	}

	@Override
	public void onImageViewDLMOutOfMemory() {
		if(!mHaveReverted) {
			General.quickToast(this, R.string.imageview_oom);
			revertToWeb();
		}
	}

	@Override
	public void onImageViewDLMException(Throwable t) {
		if(!mHaveReverted) {
			General.quickToast(this, R.string.imageview_decode_failed);
			revertToWeb();
		}
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if(mImageViewDisplayerManager != null){
			mImageViewDisplayerManager.resetTouchState();
		}
	}
}

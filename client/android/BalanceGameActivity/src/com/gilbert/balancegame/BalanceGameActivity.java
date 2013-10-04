/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gilbert.balancegame;

import java.net.MalformedURLException;
import java.util.*;

import android.R.bool;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.*;
import android.content.*;
import android.content.IntentFilter.MalformedMimeTypeException;
import android.graphics.*;
import android.graphics.BitmapFactory.Options;
import android.hardware.*;
import android.nfc.*;
import android.os.*;
import android.os.PowerManager.WakeLock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import android.view.View.OnClickListener;
import android.widget.*;
import android.widget.Chronometer.OnChronometerTickListener;
import com.microsoft.windowsazure.mobileservices.*;


/**
 * This is an example of using the accelerometer to integrate the device's
 * acceleration to a position using the Verlet method. This is illustrated with
 * a very simple particle system comprised of a few iron balls freely moving on
 * an inclined wooden table. The inclination of the virtual table is controlled
 * by the device's accelerometer.
 * 
 * @see SensorManager
 * @see SensorEvent
 * @see Sensor
 */

public class BalanceGameActivity extends Activity implements SensorEventListener {

	protected static final LostReason FINISHED = null;
	private final int GAME_MODE_PENALTY = 2;
	private final int GAME_MODE_NORMAL = 1;
	private SensorManager mSensorManager;
	private PowerManager mPowerManager;
	private WakeLock mWakeLock;
	private float mSensorX;
	private float mSensorY;
	private Sensor mAccelerometer;
	private Sensor mRotationSensor;
	private double mXRotation = 0.0;
	private double mYRotation = 0.0;
	private long mSensorTimeStamp;
	private long mCpuTimeStamp;
	private int mWarningCount;
	private long mPreviousWarningTime;
	private Chronometer mStopWatch;
	private long mStartTime;
	private long mPreviousCheatingTime;
	private double mPreviousZAcc;
	private Timer mIntroTimer;
	private int mIntroTimerSeconds;
	private Display mDisplay;
	private WindowManager mWindowManager;
	private boolean mIsRunning;

	private float circleRadiusDp;
	private SimulationView mSimulationView;
	private MobileServiceClient mClient;
	private MobileServiceTable<ScoreRecordItem> mScoreRecordTable;

	private final String serviceUrl = "https://balance.azure-mobile.net/";
	private final String appKey = "mfjasFNFXrIKZhxqbkTMAhimmMUNnf46";
	private int mGameMode;
	private int mPenalty;
	private NfcAdapter mAdapter;
	private PendingIntent mPendingIntent;
	private IntentFilter[] mFilters;
	
	private final byte[] DONE_TAG = new byte[]{-110, 7, 54, 120, 47, 110, 102, 99, 116, 108, 2, 101, 110, 90, 58, 50, 58, 67, 97, 114, 59, 69, 58, 73, 49, 59, 78, 58, 49, 53, 59, 89, 58, 109, 111, 98, 105, 46, 98, 101, 121, 111, 110, 100, 112, 111, 100, 47, 46, 117, 105, 46, 118, 105, 101, 119, 115, 46, 83, 112, 108, 97, 115, 104, 84, 15, 28, 97, 110, 100, 114, 111, 105, 100, 46, 99, 111, 109, 58, 112, 107, 103, 99, 111, 109, 46, 106, 119, 115, 111, 102, 116, 46, 110, 102, 99, 97, 99, 116, 105, 111, 110, 108, 97, 117, 110, 99, 104, 101, 114};

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		// Get an instance of the SensorManager
		mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

		// Get an instance of the PowerManager
		mPowerManager = (PowerManager) getSystemService(POWER_SERVICE);

		// Get an instance of the WindowManager
		mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
		mDisplay = mWindowManager.getDefaultDisplay();

		DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(metrics);		
		circleRadiusDp = 30 + (300 / metrics.density);

		// Create a bright wake lock
		mWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, getClass()
				.getName());

		mWarningCount = 0;
		mPreviousCheatingTime = 0;
		mIsRunning = false;

		mStopWatch = (Chronometer) findViewById(R.id.chrono);
		mStartTime = SystemClock.elapsedRealtime();
		
		final TextView timer = (TextView) findViewById(R.id.timer);
		mStopWatch.setOnChronometerTickListener(new OnChronometerTickListener(){
			@Override
			public void onChronometerTick(Chronometer arg0) {
				long countUp = (SystemClock.elapsedRealtime() - arg0.getBase()) / 1000;
				String asText = (countUp / 60) + ":" + (countUp % 60); 
				timer.setText(asText);
				if (countUp > 0 && countUp % 10 == 0) { onGravityChange(); }
			}
		});      

		findViewById(R.id.restart).setOnClickListener(new OnClickListener()
		{

			@Override
			public void onClick(View v)
			{
				onRestart(1);

			}
		});

		findViewById(R.id.restartPenalty).setOnClickListener(new OnClickListener()
		{

			@Override
			public void onClick(View v)
			{
				onRestart(2);

			}
		});
		
		mAdapter = NfcAdapter.getDefaultAdapter(this);
		
		mPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
		
		// instantiate our simulation view and set it as the activity's content
		mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		mRotationSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
		try{
			mClient = new MobileServiceClient(serviceUrl, appKey, this);
			mScoreRecordTable = mClient.getTable(ScoreRecordItem.class);
		}
		catch(MalformedURLException e)
		{
			TextView xValue = (TextView) findViewById(R.id.xValue);
			xValue.setText(String.format("Error!!" + e.getMessage()));
		}

		View view = findViewById(R.id.ballCanvasLayout);
		RelativeLayout ballLayout = (RelativeLayout) findViewById(R.id.ballCanvasLayout);
		mSimulationView = new SimulationView(this);
		ballLayout.addView(mSimulationView);
	}

	protected void onGravityChange()
	{
		mYRotation = 0;
		
		Random rnd = new Random();
		//mXRotation = 4.9 * (rnd.nextInt(3) - 1); // -1, 0 or 1
		
		final double newXRotation = 4.9 * (rnd.nextInt(3) - 1); // -1, 0 or 1
		
		if (newXRotation != mXRotation){			
			final View gravityChangeText = findViewById(R.id.gravitychangePending);
			gravityChangeText.setVisibility(View.VISIBLE);
		
			Handler h = new Handler();
			h.postDelayed(new Runnable() {
				
				@Override
				public void run() {
					mXRotation = newXRotation;	
			
					gravityChangeText.setVisibility(View.INVISIBLE);
					RelativeLayout rightArrowLayout = (RelativeLayout) findViewById(R.id.gravityRightArrowLayout);
					RelativeLayout leftArrowLayout = (RelativeLayout) findViewById(R.id.gravityLeftArrowLayout);
					
					rightArrowLayout.setVisibility(View.INVISIBLE);
					leftArrowLayout.setVisibility(View.INVISIBLE);
					
					if (mXRotation > 0)
					{
						leftArrowLayout.setVisibility(View.VISIBLE);
						rightArrowLayout.setVisibility(View.INVISIBLE);
					}
					
					if (mXRotation < 0)
					{
						rightArrowLayout.setVisibility(View.VISIBLE);
						leftArrowLayout.setVisibility(View.INVISIBLE);
					}
				}
			}, 5000);
		}
 }	

	@Override
	protected void onResume() {
		super.onResume();
		/*
		 * when the activity is resumed, we acquire a wake-lock so that the
		 * screen stays on, since the user will likely not be fiddling with the
		 * screen or buttons.
		 */
		mWakeLock.acquire();
		
		if (mAdapter != null) mAdapter.enableForegroundDispatch(this, mPendingIntent, null,
                null);
	}

	@Override
	protected void onPause() {
		super.onPause();
		
		if (mAdapter != null) mAdapter.disableForegroundDispatch(this);
		/*
		 * When the activity is paused, we make sure to stop the simulation,
		 * release our sensor resources and wake locks
		 */

		// and release our wake-lock
		mWakeLock.release();
	}

	@Override
    public void onNewIntent(Intent intent) {
        Log.i("Foreground dispatch", "Discovered tag with intent: " + intent);
        Parcelable[] messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        
        if (messages != null && messages.length > 0 && messages[0] instanceof NdefMessage)
        {
        	NdefMessage wantedTag = (NdefMessage) messages[0];
        	if (mIsRunning)
        	{
        		if (Arrays.equals(wantedTag.toByteArray(), DONE_TAG))
        		{
        			runOnUiThread(new Runnable()
					{
						
						@Override
						public void run()
						{
							end(FINISHED);							
						}
					});
        			
        		}
        	}
        }
        
    }
	
	public void startSimulation() {

		if (mIntroTimer != null) {
			mIntroTimer.cancel();
		}
		mIntroTimer = new Timer();

		final TextView timerCaption = (TextView)BalanceGameActivity.this.findViewById(R.id.initTimerCaption);
		timerCaption.setVisibility(View.VISIBLE);

		mIntroTimer.schedule(new TimerTask()
		{

			@Override
			public void run()
			{
				BalanceGameActivity.this.runOnUiThread(new Runnable()
				{

					@Override
					public void run()
					{
						mIntroTimerSeconds++;


						if (mIntroTimerSeconds <= 3) {
							timerCaption.setText(String.valueOf(mIntroTimerSeconds));
						} else {
							timerCaption.setVisibility(View.GONE);

							mIntroTimer.cancel();

							/*
							 * It is not necessary to get accelerometer events at a very high
							 * rate, by using a slower rate (SENSOR_DELAY_UI), we get an
							 * automatic low-pass filter, which "extracts" the gravity component
							 * of the acceleration. As an added benefit, we use less power and
							 * CPU resources.
							 */
							mIsRunning = true;
							mSensorManager.registerListener(BalanceGameActivity.this, mAccelerometer, SensorManager.SENSOR_DELAY_GAME);
							mSensorManager.registerListener(BalanceGameActivity.this, mRotationSensor, SensorManager.SENSOR_DELAY_GAME);
							mStopWatch.setBase(SystemClock.elapsedRealtime());
							mStopWatch.start();
						}

					}
				});

			}
		}, 0, 1000);
	}

	public void stopSimulation() {
		mIsRunning = false;
		mSensorManager.unregisterListener(this);
		mStopWatch.stop();        
		if (mIntroTimer != null) {
			mIntroTimer.cancel();
		}
		((TextView)BalanceGameActivity.this.findViewById(R.id.initTimerCaption)).setVisibility(View.GONE);
	}

	public void onRestart(int i)
	{
		mGameMode = i;
		mWarningCount = 0;
		mPreviousCheatingTime = 0;
		mIntroTimerSeconds = 0;
		mPenalty = 0;
		mSensorX = 0;
		mSensorY = 0;
		mSimulationView.onRestartClicked();
		findViewById(R.id.warning_1).setVisibility(View.GONE);
		findViewById(R.id.warning_2).setVisibility(View.GONE);
		findViewById(R.id.warning_3).setVisibility(View.GONE);
		findViewById(R.id.warning_4).setVisibility(View.GONE);
		findViewById(R.id.warning_5).setVisibility(View.GONE);
		findViewById(R.id.lostReason).setVisibility(View.INVISIBLE);
		findViewById(R.id.buttonsLayout).setVisibility(View.GONE);
		findViewById(R.id.penalty).setVisibility(View.GONE);
		mStopWatch.setBase(SystemClock.elapsedRealtime());
		startSimulation();
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
		{
			/*
			 * record the accelerometer data, the event's timestamp as well as
			 * the current time. The latter is needed so we can calculate the
			 * "present" time during rendering. In this application, we need to
			 * take into account how the screen is rotated with respect to the
			 * sensors (which always return data in a coordinate space aligned
			 * to with the screen in its native orientation).
			 */

			TextView xValue = (TextView) findViewById(R.id.xValue);
			TextView yValue = (TextView) findViewById(R.id.yValue);
			TextView zValue = (TextView) findViewById(R.id.zValue);

			TextView xRotValue = (TextView) findViewById(R.id.xRotValue);
			TextView yRotValue = (TextView) findViewById(R.id.yRotValue);

			double xAcc = Math.abs(event.values[0] + mXRotation);
			double yAcc = Math.abs(event.values[1] + mYRotation);
			double zAcc = Math.abs(Math.abs(event.values[2]) - 9.81 + mXRotation + mYRotation);


			xValue.setText(String.format("%.2f", xAcc));
			yValue.setText(String.format("%.2f", yAcc));
			zValue.setText(String.format("%.2f", zAcc));
			xRotValue.setText(String.format("%.2f", mXRotation));
			yRotValue.setText(String.format("%.2f", mYRotation));

			//			if (mGameMode == GAME_MODE_NORMAL) {
			//				if (zAcc > 9) {
			//					end(LostReason.DROPPED);
			//					return;
			//				} else if (yAcc > 2 || xAcc > 2 || zAcc > 2) {
			//					if (mWarningCount == 5) {
			//						end(LostReason.LOST_BALANCE);
			//						return;
			//					} else {
			//						increaseWarning();
			//					}
			//				}
			//			}

			if (Math.abs(mPreviousZAcc - zAcc) < 0.05) {
				if (mPreviousCheatingTime == 0) {
					mPreviousCheatingTime = System.currentTimeMillis();
				} else if (System.currentTimeMillis() - mPreviousCheatingTime > 1000) {
					end(LostReason.CHEATED);
					return;
				}
			} else {
				mPreviousCheatingTime = 0;
			}
			mPreviousZAcc = zAcc;

			/*
			 * record the accelerometer data, the event's timestamp as well as
			 * the current time. The latter is needed so we can calculate the
			 * "present" time during rendering. In this application, we need to
			 * take into account how the screen is rotated with respect to the
			 * sensors (which always return data in a coordinate space aligned
			 * to with the screen in its native orientation).
			 */

			switch (mDisplay.getRotation()) {
				case Surface.ROTATION_0:
					mSensorX = event.values[0];
					mSensorY = event.values[1];
					break;
				case Surface.ROTATION_90:
					mSensorX = -event.values[1];
					mSensorY = event.values[0];
					break;
				case Surface.ROTATION_180:
					mSensorX = -event.values[0];
					mSensorY = -event.values[1];
					break;
				case Surface.ROTATION_270:
					mSensorX = event.values[1];
					mSensorY = -event.values[0];
					break;
			}

			mSensorTimeStamp = event.timestamp;
			mCpuTimeStamp = System.nanoTime();
		}
	}

	/**
	 * 
	 */
	private void increaseWarning()
	{
		if (mIsRunning) {
			if (mPreviousWarningTime == 0 || System.currentTimeMillis() - mPreviousWarningTime > 1000) {
				mPreviousWarningTime = System.currentTimeMillis();

				mPenalty++;

				TextView penaltyText = (TextView) findViewById(R.id.penalty);
				penaltyText.setVisibility(View.VISIBLE);
				penaltyText.setText(String.format("+ %d", mPenalty));
			} else {
				return;
			}

			if (mGameMode == GAME_MODE_NORMAL) {
				View warning = null;

				switch (mWarningCount) {
					case 0:
						warning = findViewById(R.id.warning_1);
						break;

					case 1:
						warning = findViewById(R.id.warning_2);
						break;

					case 2:
						warning = findViewById(R.id.warning_3);
						break;


					case 3:
						warning = findViewById(R.id.warning_4);
						break;

					case 4:
						warning = findViewById(R.id.warning_5);
						break;

					default:
						break;
				}

				if (warning != null) {
					warning.setVisibility(View.VISIBLE);
					mWarningCount++;
				}
			} else {
				findViewById(R.id.warningBorder).setVisibility(View.VISIBLE);

				final Handler handler = new Handler();
				handler.postDelayed(new Runnable()
				{

					@Override
					public void run()
					{
						findViewById(R.id.warningBorder).setVisibility(View.GONE);

					}
				}, 500);
			}
		}


	}

	/**
	 * @param reason
	 */
	private void end(LostReason reason)
	{
		TextView lostReasonText = (TextView) findViewById(R.id.lostReason);
		lostReasonText.setVisibility(View.VISIBLE);
		if (reason == LostReason.DROPPED) {
			lostReasonText.setText("Dropped your phone!");
		} else if (reason == LostReason.LOST_BALANCE){
			lostReasonText.setText("Lost balance!");
		} else {
			lostReasonText.setText("No cheating!!!");
		}
		
		if (reason == FINISHED) {
			lostReasonText.setVisibility(View.GONE);
		}

		findViewById(R.id.buttonsLayout).setVisibility(View.VISIBLE);

		stopSimulation();

		ScoreRecordItem score = new ScoreRecordItem();
		
		score.name = "Tesr";
		
		AccountManager accountManager = AccountManager.get(this);
	    Account[] accounts = accountManager.getAccounts();
	    
	    for (Account account : accounts) {
			if (account.type.contains("facebook") || account.type.contains("google")) {
				score.name = account.name;
				break;
			}
		}
	    
		score.score = (int) ((SystemClock.elapsedRealtime() - mStopWatch.getBase()) / 1000) + mPenalty;
		final BalanceGameActivity ctx = this;

		mScoreRecordTable.insert(score, new TableOperationCallback<ScoreRecordItem>() {

			@Override
			public void onCompleted(ScoreRecordItem entity,
					Exception exception, ServiceFilterResponse response) {
				if (exception != null)
				{
					if (firstError)
					{
						(new AlertDialog.Builder(ctx)).setMessage(exception.getMessage()).show();
						firstError = false;
					}
				}

			}
		});

	}
	private Boolean firstError=true;

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}

	class SimulationView extends View {
		// diameter of the balls in meters
		private static final float sBallDiameter = 0.004f;
		private static final float sBallDiameter2 = sBallDiameter * sBallDiameter;

		// friction of the virtual table and air
		private static final float sFriction = 0.1f;

		private long mLastT;
		private float mLastDeltaT;

		private float mXDpi;
		private float mYDpi;
		private float mMetersToPixelsX;
		private float mMetersToPixelsY;
		private Bitmap mBitmap;
		private Bitmap mWood;
		private float mXOrigin;
		private float mYOrigin;
		private int mViewHeight;
		private int mViewWidth;

		private long mSensorTimeStamp;
		private long mCpuTimeStamp;
		private float mHorizontalBound;
		private float mVerticalBound;
		private ParticleSystem mParticleSystem = new ParticleSystem();

		/*
		 * Each of our particle holds its previous and current position, its
		 * acceleration. for added realism each particle has its own friction
		 * coefficient.
		 */
		class Particle {
			private float mPosX;
			private float mPosY;
			private float mAccelX;
			private float mAccelY;
			private float mLastPosX;
			private float mLastPosY;
			private float mOneMinusFriction;

			Particle() {
				// make each particle a bit different by randomizing its
				// coefficient of friction
				final float r = ((float) Math.random() - 0.5f) * 0.2f;
				mOneMinusFriction = 0.6f - sFriction + r;
			}

			public void computePhysics(float sx, float sy, float dT, float dTC) {
				// Force of gravity applied to our virtual object
				final float m = 1000.0f; // mass of our virtual object
				final float gx = -sx * m;
				final float gy = -sy * m;

				/*
				 * ·F = mA <=> A = ·F / m We could simplify the code by
				 * completely eliminating "m" (the mass) from all the equations,
				 * but it would hide the concepts from this sample code.
				 */
				final float invm = 1.0f / m;
				final float ax = gx * invm;
				final float ay = gy * invm;

				/*
				 * Time-corrected Verlet integration The position Verlet
				 * integrator is defined as x(t+Æt) = x(t) + x(t) - x(t-Æt) +
				 * a(t)Ætö2 However, the above equation doesn't handle variable
				 * Æt very well, a time-corrected version is needed: x(t+Æt) =
				 * x(t) + (x(t) - x(t-Æt)) * (Æt/Æt_prev) + a(t)Ætö2 We also add
				 * a simple friction term (f) to the equation: x(t+Æt) = x(t) +
				 * (1-f) * (x(t) - x(t-Æt)) * (Æt/Æt_prev) + a(t)Ætö2
				 */
				final float dTdT = dT * dT;
				final float x = mPosX + mOneMinusFriction * dTC * (mPosX - mLastPosX) + mAccelX
						* dTdT;
				final float y = mPosY + mOneMinusFriction * dTC * (mPosY - mLastPosY) + mAccelY
						* dTdT;
				mLastPosX = mPosX;
				mLastPosY = mPosY;
				mPosX = x;
				mPosY = y;
				mAccelX = ax;
				mAccelY = ay;
			}

			/*
			 * Resolving constraints and collisions with the Verlet integrator
			 * can be very simple, we simply need to move a colliding or
			 * constrained particle in such way that the constraint is
			 * satisfied.
			 */
			public void resolveCollisionWithBounds() {
				final float xmax = mHorizontalBound;
				final float ymax = mVerticalBound;
				final float x = mPosX;
				final float y = mPosY;
				if (x > xmax) {
					mPosX = xmax;
				} else if (x < -xmax) {
					mPosX = -xmax;
				}
				if (y > ymax) {
					mPosY = ymax;
				} else if (y < -ymax) {
					mPosY = -ymax;
				}
			}
		}

		/*
		 * A particle system is just a collection of particles
		 */
		class ParticleSystem {
			static final int NUM_PARTICLES = 1;
			private Particle mBalls[] = new Particle[NUM_PARTICLES];

			ParticleSystem() {
				/*
				 * Initially our particles have no speed or acceleration
				 */
				for (int i = 0; i < mBalls.length; i++) {
					mBalls[i] = new Particle();
				}
			}

			/*
			 * Update the position of each particle in the system using the
			 * Verlet integrator.
			 */
			private void updatePositions(float sx, float sy, long timestamp) {
				final long t = timestamp;
				if (mLastT != 0) {
					final float dT = (float) (t - mLastT) * (1.0f / 1000000000.0f);
					if (mLastDeltaT != 0) {
						final float dTC = dT / mLastDeltaT;
						final int count = mBalls.length;
						for (int i = 0; i < count; i++) {
							Particle ball = mBalls[i];
							ball.computePhysics(sx, sy, dT, dTC);
						}
					}
					mLastDeltaT = dT;
				}
				mLastT = t;
			}

			/*
			 * Performs one iteration of the simulation. First updating the
			 * position of all the particles and resolving the constraints and
			 * collisions.
			 */
			public void update(float sx, float sy, long now) {
				// update the system's positions
				updatePositions(sx, sy, now);

				// We do no more than a limited number of iterations
				final int NUM_MAX_ITERATIONS = 10;

				/*
				 * Resolve collisions, each particle is tested against every
				 * other particle for collision. If a collision is detected the
				 * particle is moved away using a virtual spring of infinite
				 * stiffness.
				 */
				boolean more = true;
				final int count = mBalls.length;
				for (int k = 0; k < NUM_MAX_ITERATIONS && more; k++) {
					more = false;
					for (int i = 0; i < count; i++) {
						Particle curr = mBalls[i];
						for (int j = i + 1; j < count; j++) {
							Particle ball = mBalls[j];
							float dx = ball.mPosX - curr.mPosX;
							float dy = ball.mPosY - curr.mPosY;
							float dd = dx * dx + dy * dy;
							// Check for collisions
							if (dd <= sBallDiameter2) {
								/*
								 * add a little bit of entropy, after nothing is
								 * perfect in the universe.
								 */
								dx += ((float) Math.random() - 0.5f) * 0.0001f;
								dy += ((float) Math.random() - 0.5f) * 0.0001f;
								dd = dx * dx + dy * dy;
								// simulate the spring
								final float d = (float) Math.sqrt(dd);
								final float c = (0.5f * (sBallDiameter - d)) / d;
								curr.mPosX -= dx * c;
								curr.mPosY -= dy * c;
								ball.mPosX += dx * c;
								ball.mPosY += dy * c;
								more = true;
							}
						}
						/*
						 * Finally make sure the particle doesn't intersects
						 * with the walls.
						 */
						curr.resolveCollisionWithBounds();
					}
				}
			}

			public int getParticleCount() {
				return mBalls.length;
			}

			public float getPosX(int i) {
				return mBalls[i].mPosX;
			}

			public float getPosY(int i) {
				return mBalls[i].mPosY;
			}

			public void reset()
			{
				for (Particle ball : mBalls)
				{
					ball.mPosX = 0;
					ball.mPosY = 0;
				}
			}
		}

		public SimulationView(Context context) {
			super(context);

			DisplayMetrics metrics = new DisplayMetrics();
			getWindowManager().getDefaultDisplay().getMetrics(metrics);
			mXDpi = metrics.xdpi;
			mYDpi = metrics.ydpi;
			mMetersToPixelsX = mXDpi / 0.0254f;
			mMetersToPixelsY = mYDpi / 0.0254f;

			// rescale the ball so it's about 0.5 cm on screen
			Bitmap ball = BitmapFactory.decodeResource(getResources(), R.drawable.ball);
			final int dstWidth = (int) (sBallDiameter * mMetersToPixelsX + 0.5f);
			final int dstHeight = (int) (sBallDiameter * mMetersToPixelsY + 0.5f);
			mBitmap = Bitmap.createScaledBitmap(ball, dstWidth, dstHeight, true);

			Options opts = new Options();
			opts.inDither = true;
			opts.inPreferredConfig = Bitmap.Config.RGB_565;
			mWood = BitmapFactory.decodeResource(getResources(), R.drawable.wood, opts);
		}

		public void onRestartClicked()
		{
			mXOrigin = (mViewWidth - mBitmap.getWidth()) * 0.5f;
			mYOrigin = (mViewHeight - mBitmap.getHeight()) * 0.5f;
			mParticleSystem = new ParticleSystem();
		}

		@Override
		protected void onSizeChanged(int w, int h, int oldw, int oldh) {

			mViewHeight = h;
			mViewWidth = w;

			// compute the origin of the screen relative to the origin of
			// the bitmap
			mXOrigin = (w - mBitmap.getWidth()) * 0.5f;
			mYOrigin = (h - mBitmap.getHeight()) * 0.5f;
			mHorizontalBound = ((w / mMetersToPixelsX - sBallDiameter) * 0.5f);
			mVerticalBound = ((h / mMetersToPixelsY - sBallDiameter) * 0.5f);
		}

		@Override
		protected void onDraw(Canvas canvas) {

			/*
			 * draw the background
			 */

			//canvas.drawBitmap(mWood, 0, 0, null);

			/*
			 * compute the new position of our object, based on accelerometer
			 * data and present time.
			 */

			final ParticleSystem particleSystem = mParticleSystem;
			final long now = mSensorTimeStamp + (System.nanoTime() - mCpuTimeStamp);
			final float sx = (float) (mSensorX - mXRotation);
			//final float sx = mSensorX - 4.9f;
			final float sy = mSensorY;

			particleSystem.update(sx, sy, now);

			final float xc = mXOrigin;
			final float yc = mYOrigin;
			final float xs = mMetersToPixelsX;
			final float ys = mMetersToPixelsY;
			final Bitmap bitmap = mBitmap;
			final int count = particleSystem.getParticleCount();
			for (int i = 0; i < count; i++) {
				/*
				 * We transform the canvas so that the coordinate system matches
				 * the sensors coordinate system with the origin in the center
				 * of the screen and the unit is the meter.
				 */

				final float x = xc + particleSystem.getPosX(i) * xs;
				final float y = yc - particleSystem.getPosY(i) * ys;

				canvas.drawBitmap(bitmap, x, y, null);

				/*
				 * Calculate the coordinates of the target circle
				 */
				double xCircle = Math.pow(x - mXOrigin, 2);
				double yCircle = Math.pow(y - mYOrigin, 2);
				double r = Math.pow(circleRadiusDp, 2);

				if (xCircle + yCircle > r) {

					if (mWarningCount == 5 && (mGameMode == GAME_MODE_NORMAL)) {
						end(LostReason.LOST_BALANCE);
						return;
					} else {
						increaseWarning();
					}					
				}
			}

			// and make sure to redraw asap
			invalidate();
		}
	}
}

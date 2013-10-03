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

import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.BitmapFactory.Options;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.MailTo;
import android.os.*;
import android.os.PowerManager.WakeLock;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.View.OnClickListener;
import android.widget.*;
import android.widget.Chronometer.OnChronometerTickListener;
import com.microsoft.windowsazure.mobileservices.MobileServiceClient;
import com.microsoft.windowsazure.mobileservices.MobileServiceTable;
import com.microsoft.windowsazure.mobileservices.ServiceFilter;
import com.microsoft.windowsazure.mobileservices.NextServiceFilterCallback;
import com.microsoft.windowsazure.mobileservices.ServiceFilterRequest;
import com.microsoft.windowsazure.mobileservices.ServiceFilterResponse;
import com.microsoft.windowsazure.mobileservices.ServiceFilterResponseCallback;
import com.microsoft.windowsazure.mobileservices.TableOperationCallback;
import com.microsoft.windowsazure.mobileservices.TableQueryCallback;
import java.net.MalformedURLException;

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

	private SensorManager mSensorManager;
	private PowerManager mPowerManager;
	private WakeLock mWakeLock;

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
	
	private MobileServiceClient mClient;
	private MobileServiceTable<ScoreRecordItem> mScoreRecordTable;
	
	private final String serviceUrl = "https://balance.azure-mobile.net/";
	private final String appKey = "mfjasFNFXrIKZhxqbkTMAhimmMUNnf46";

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		// Get an instance of the SensorManager
		mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

		// Get an instance of the PowerManager
		mPowerManager = (PowerManager) getSystemService(POWER_SERVICE);

		// Create a bright wake lock
		mWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, getClass()
				.getName());

		mWarningCount = 0;
		mPreviousCheatingTime = 0;

		mStopWatch = (Chronometer) findViewById(R.id.chrono);
		mStartTime = SystemClock.elapsedRealtime();

		final TextView timer = (TextView) findViewById(R.id.timer);
		mStopWatch.setOnChronometerTickListener(new OnChronometerTickListener(){
			@Override
			public void onChronometerTick(Chronometer arg0) {
				long countUp = (SystemClock.elapsedRealtime() - arg0.getBase()) / 1000;
				// Set visible clock:
				String asText = (countUp / 60) + ":" + (countUp % 60); 
				timer.setText(asText);
				
				// Change gravity every 5 seconds:
				if (countUp % 5 == 0) { onGravityChange(); }
			}
		});      

		findViewById(R.id.restart).setOnClickListener(new OnClickListener()
		{

			@Override
			public void onClick(View v)
			{
				onRestart();

			}
		});

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

	}
	protected void onGravityChange()
	{
		Random rnd = new Random();
		mXRotation = 9.81 * (rnd.nextInt(3) - 1); // -1, 0 or 1
		//mYRotation = 9.81 * (rnd.nextInt(2) - 1); // -1 or 0
		mYRotation = 0;
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

		// Start the simulation
		startSimulation();
	}

	@Override
	protected void onPause() {
		super.onPause();
		/*
		 * When the activity is paused, we make sure to stop the simulation,
		 * release our sensor resources and wake locks
		 */

		// Stop the simulation
		stopSimulation();

		// and release our wake-lock
		mWakeLock.release();
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
		mSensorManager.unregisterListener(this);
		mStopWatch.stop();        
		mIntroTimer.cancel();
		((TextView)BalanceGameActivity.this.findViewById(R.id.initTimerCaption)).setVisibility(View.GONE);
	}

	public void onRestart()
	{
		mWarningCount = 0;
		mPreviousCheatingTime = 0;
		mIntroTimerSeconds = 0;
		findViewById(R.id.warning_1).setVisibility(View.GONE);
		findViewById(R.id.warning_2).setVisibility(View.GONE);
		findViewById(R.id.warning_3).setVisibility(View.GONE);
		findViewById(R.id.warning_4).setVisibility(View.GONE);
		findViewById(R.id.warning_5).setVisibility(View.GONE);
		findViewById(R.id.lostReason).setVisibility(View.INVISIBLE);
		findViewById(R.id.restart).setVisibility(View.GONE);
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
				
			xRotValue.setText(String.format("%.2f", mXRotation / 9.81));
			yRotValue.setText(String.format("%.2f", mYRotation / 9.81));
			
			if (zAcc > 9) {
				//end(LostReason.DROPPED);
				//return;
			} else if (yAcc > 2 || xAcc > 2 || zAcc > 2) {
				if (mWarningCount == 5) {
					end(LostReason.LOST_BALANCE);
					return;
				} else {
					increaseWarning();
				}
			}
	
			if (Math.abs(mPreviousZAcc - zAcc) < 0.05) {
				if (mPreviousCheatingTime == 0) {
					mPreviousCheatingTime = System.currentTimeMillis();
				} else if (System.currentTimeMillis() - mPreviousCheatingTime > 1000) {
					//end(LostReason.CHEATED);
					//return;
				}
			} else {
				mPreviousCheatingTime = 0;
			}
			mPreviousZAcc = zAcc;
		}
		
		if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR)
		{
			//double xRot = event.values[0];
			//TextView xValue = (TextView) findViewById(R.id.xRotValue);
			//xValue.setText(String.format("%.2f", xRot));
		}
	}

	/**
	 * 
	 */
	private void increaseWarning()
	{
		if (mPreviousWarningTime == 0 || System.currentTimeMillis() - mPreviousWarningTime > 1000) {
			mPreviousWarningTime = System.currentTimeMillis();
		} else {
			return;
		}

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

		findViewById(R.id.restart).setVisibility(View.VISIBLE);
		
		stopSimulation();
		
		ScoreRecordItem score = new ScoreRecordItem();
		score.name = "Tesr";
		score.score = 10;
		final BalanceGameActivity ctx = this;
		mScoreRecordTable.insert(score, new TableOperationCallback<ScoreRecordItem>() {

			@Override
			public void onCompleted(ScoreRecordItem entity,
					Exception exception, ServiceFilterResponse response) {
				if (exception != null)
				{
				(new AlertDialog.Builder(ctx)).setMessage(exception.getMessage()).show();
				}
				
			}
		});
		
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}
}

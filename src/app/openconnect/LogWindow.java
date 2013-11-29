package app.openconnect;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.*;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.IBinder;
import android.os.Message;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.widget.AdapterView.OnItemLongClickListener;
import app.openconnect.core.OpenConnectManagementThread;
import app.openconnect.core.OpenVPN;
import app.openconnect.core.OpenVPN.ConnectionStatus;
import app.openconnect.core.OpenVPN.LogItem;
import app.openconnect.core.OpenVPN.LogListener;
import app.openconnect.core.OpenVPN.StateListener;
import app.openconnect.core.OpenVpnService;
import app.openconnect.core.OpenVpnService.LocalBinder;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Vector;

public class LogWindow extends ListActivity implements StateListener  {
	public static final String TAG = "OpenConnect";

	private static final String LOGTIMEFORMAT = "logtimeformat";

	protected BroadcastReceiver mReceiver; 
	protected OpenVpnService mService;

    private MenuItem mCancelButton;
    private boolean mDisconnected;

	private ServiceConnection mConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName className,
				IBinder service) {
			// We've bound to LocalService, cast the IBinder and get LocalService instance
			LocalBinder binder = (LocalBinder) service;
			mService = binder.getService();
			updateUI();
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			mService = null;
			Log.w(TAG, "unbound from OpenVpnService");
		}
	};

	class LogWindowListAdapter implements ListAdapter, LogListener, Callback {

		private static final int MESSAGE_NEWLOG = 0;

		private static final int MESSAGE_CLEARLOG = 1;
		
		private static final int MESSAGE_NEWTS = 2;

		private Vector<LogItem> myEntries=new Vector<LogItem>();

		private Handler mHandler;

		private Vector<DataSetObserver> observers=new Vector<DataSetObserver>();

		private int mTimeFormat=0;


		public LogWindowListAdapter() {
			initLogBuffer();

			if (mHandler == null) {
				mHandler = new Handler(this);
			}

			OpenVPN.addLogListener(this);
		}



		private void initLogBuffer() {
			myEntries.clear();
            Collections.addAll(myEntries, OpenVPN.getlogbuffer());
		}

		String getLogStr() {
			String str = "";
			for(LogItem entry:myEntries) {
				str+=entry.getString(LogWindow.this) + '\n';
			}
			return str;
		}


		private void shareLog() {
			Intent shareIntent = new Intent(Intent.ACTION_SEND);
			shareIntent.putExtra(Intent.EXTRA_TEXT, getLogStr());
			shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ics_openvpn_log_file));
			shareIntent.setType("text/plain");
			startActivity(Intent.createChooser(shareIntent, "Send Logfile"));
		}

		@Override
		public void registerDataSetObserver(DataSetObserver observer) {
			observers.add(observer);

		}

		@Override
		public void unregisterDataSetObserver(DataSetObserver observer) {
			observers.remove(observer);
		}

		@Override
		public int getCount() {
			return myEntries.size();
		}

		@Override
		public Object getItem(int position) {
			return myEntries.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public boolean hasStableIds() {
			return true;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			TextView v;
			if(convertView==null)
				v = new TextView(getBaseContext());
			else
				v = (TextView) convertView;
			
			LogItem le = myEntries.get(position);
			String msg = le.getString(LogWindow.this);
			if (mTimeFormat != 0) {
				Date d = new Date(le.getLogtime());
				java.text.DateFormat timeformat;
				if (mTimeFormat==2) 
					timeformat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault());
				else
					timeformat = DateFormat.getTimeFormat(LogWindow.this);
				String time = timeformat.format(d);
				msg =  time + " " + msg;
			}
			v.setText(msg);
			return v;
		}

		@Override
		public int getItemViewType(int position) {
			return 0;
		}

		@Override
		public int getViewTypeCount() {
			return 1;
		}

		@Override
		public boolean isEmpty() {
			return myEntries.isEmpty();

		}

		@Override
		public boolean areAllItemsEnabled() {
			return true;
		}

		@Override
		public boolean isEnabled(int position) {
			return true;
		}

		@Override
		public void newLog(LogItem logmessage) {
			Message msg = Message.obtain();
            assert (msg!=null);
			msg.what=MESSAGE_NEWLOG;
			Bundle mbundle=new Bundle();
			mbundle.putParcelable("logmessage", logmessage);
			msg.setData(mbundle);
			mHandler.sendMessage(msg);
		}

		@Override
		public boolean handleMessage(Message msg) {
			// We have been called
			if(msg.what==MESSAGE_NEWLOG) {

				LogItem logmessage = msg.getData().getParcelable("logmessage");
				myEntries.add(logmessage);

				for (DataSetObserver observer : observers) {
					observer.onChanged();
				}
			} else if (msg.what == MESSAGE_CLEARLOG) {
				initLogBuffer();
				for (DataSetObserver observer : observers) {
					observer.onInvalidated();
				}
			}  else if (msg.what == MESSAGE_NEWTS) {
				for (DataSetObserver observer : observers) {
					observer.onInvalidated();
				}
			}

			return true;
		}

		void clearLog() {
			// Actually is probably called from GUI Thread as result of the user 
			// pressing a button. But better safe than sorry
			OpenVPN.clearLog();
			OpenVPN.logMessage(0,"","Log cleared.");
			mHandler.sendEmptyMessage(MESSAGE_CLEARLOG);
		}



		public void nextTimeFormat() {
			mTimeFormat= (mTimeFormat+ 1) % 3;
			mHandler.sendEmptyMessage(MESSAGE_NEWTS);
		}
		
	}



	private LogWindowListAdapter ladapter;
	private TextView mSpeedView;

    @Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if(item.getItemId()==R.id.clearlog) {
			ladapter.clearLog();
			return true;
		} else if(item.getItemId()==R.id.cancel) {
			if (mDisconnected) {
				if (mService != null) {
					mService.startReconnectActivity(this);
				}
			} else {
				stopVPN();
			}
            return true;
        } else if(item.getItemId()==R.id.send) {
			ladapter.shareLog();
		} else if(item.getItemId() == R.id.toggle_time) {
			ladapter.nextTimeFormat();
		} else if(item.getItemId() == android.R.id.home) {
			// This is called when the Home (Up) button is pressed
			// in the Action Bar.
			Intent parentActivityIntent = new Intent(this, MainActivity.class);
			parentActivityIntent.addFlags(
					Intent.FLAG_ACTIVITY_CLEAR_TOP |
					Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(parentActivityIntent);
			finish();
			return true;

		}
		return super.onOptionsItemSelected(item);

	}

    @Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.logmenu, menu);
		mCancelButton = menu.findItem(R.id.cancel);
		updateUI();
		return true;
	}

    private synchronized void updateUI() {
    	if (mService != null) {
    		mService.startActiveDialog(this);

    		int state = mService.getConnectionState();
    		if (mCancelButton != null) {
    			String title;
    			if (state == OpenConnectManagementThread.STATE_DISCONNECTED) {
    				title = getString(R.string.reconnect);
    				mCancelButton.setIcon(android.R.drawable.ic_menu_rotate);
    				mDisconnected = true;
    			} else {
    				title = getString(R.string.disconnect);
    				mCancelButton.setIcon(android.R.drawable.ic_menu_close_clear_cancel);
    				mDisconnected = false;
    			}
				mCancelButton.setTitle(title);
				mCancelButton.setTitleCondensed(title);
    		}
    		String states[] = getResources().getStringArray(R.array.connection_states);
    		mSpeedView.setText(states[state]);
    	}
    }

	@Override
	protected void onStart() {
		super.onStart();

        Intent intent = new Intent(this, OpenVpnService.class);
        intent.setAction(OpenVpnService.START_SERVICE);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(getPackageName() + ".VPN_STATUS");

        mReceiver = new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
        	  updateUI();
          }
        };
        registerReceiver(mReceiver, filter);
	}

	@Override
	protected void onStop() {
    	if (mService != null) {
    		mService.stopActiveDialog();
    	}
        unbindService(mConnection);
        unregisterReceiver(mReceiver);
		super.onStop();
    }

    @Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.logwindow);
		ListView lv = getListView();

		lv.setOnItemLongClickListener(new OnItemLongClickListener() {

			@Override
			public boolean onItemLongClick(AdapterView<?> parent, View view,
					int position, long id) {
				ClipboardManager clipboard = (ClipboardManager)
						getSystemService(Context.CLIPBOARD_SERVICE);
				ClipData clip = ClipData.newPlainText("Log Entry",((TextView) view).getText());
				clipboard.setPrimaryClip(clip);
				Toast.makeText(getBaseContext(), R.string.copied_entry, Toast.LENGTH_SHORT).show();
				return true;
			}
		});

		ladapter = new LogWindowListAdapter();
		ladapter.mTimeFormat = getPreferences(0).getInt(LOGTIMEFORMAT, 0);
		lv.setAdapter(ladapter);

		mSpeedView = (TextView) findViewById(R.id.speed);
		getActionBar().setDisplayHomeAsUpEnabled(true);
    }


    @Override
	public void updateState(final String status,final String logmessage, final int resid, final ConnectionStatus level) {
		runOnUiThread(new Runnable() {

			@Override
			public void run() {
				String prefix=getString(resid) + ":";
				if (status.equals("BYTECOUNT") || status.equals("NOPROCESS") )
					prefix="";
				if (resid==R.string.unknown_state)
					prefix+=status;
				mSpeedView.setText(prefix + logmessage);
			}
		});
	}

    private void stopVPN() {
    	if (mService != null) {
    		mService.stopVPN();
    	}
    }

    private void handleBackButton() {
    	stopVPN();
    	super.onBackPressed();
    }

    @Override
    public void onBackPressed() {
    	if (mDisconnected) {
    		super.onBackPressed();
    		return;
    	}
    	new AlertDialog.Builder(this)
    		.setTitle(R.string.cancel_connection_long)
    		.setMessage(R.string.cancel_connection_query)
    		.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					handleBackButton();
				}
    		})
    		.setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
				}
    		})
    		.create()
    		.show();
    }

	@Override
	protected void onDestroy() {
		OpenVPN.removeLogListener(ladapter);
		super.onDestroy();
	}

}
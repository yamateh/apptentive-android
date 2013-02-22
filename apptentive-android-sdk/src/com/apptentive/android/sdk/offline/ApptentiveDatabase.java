/*
 * Copyright (c) 2013, Apptentive, Inc. All Rights Reserved.
 * Please refer to the LICENSE file for the terms and conditions
 * under which redistribution and use of this file is permitted.
 */

package com.apptentive.android.sdk.offline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.apptentive.android.sdk.Log;
import com.apptentive.android.sdk.model.*;
import com.apptentive.android.sdk.module.messagecenter.model.FileMessage;
import com.apptentive.android.sdk.storage.PayloadStore;

import java.util.ArrayList;
import java.util.List;

/**
 * There can be only one. SQLiteOpenHelper per database name that is. All new Apptentive tables must be defined here.
 *
 * @author Sky Kelsey
 */
public class ApptentiveDatabase extends SQLiteOpenHelper implements RecordStore, MessageStore, EventStore, PayloadStore, KeyValueStore {

	// COMMON
	private static int DATABASE_VERSION = 1;
	private static final String DATABASE_NAME = "apptentive";

	// RECORD
	private static final String TABLE_RECORD = "record";
	private static final String RECORD_KEY_DB_ID = "_id";                           // 0
	private static final String RECORD_KEY_ID = "id";                               // 1
	private static final String RECORD_KEY_BASE_TYPE = "base_type";                 // 2
	private static final String RECORD_KEY_CREATED_AT = "created_at";               // 3
	private static final String RECORD_KEY_CLIENT_CREATED_AT = "client_created_at"; // 4
	private static final String RECORD_KEY_NONCE = "nonce";                         // 5
	private static final String RECORD_KEY_STATE = "state";                         // 6
	private static final String RECORD_KEY_JSON = "json";                           // 7

	private static final String TABLE_CREATE_RECORD =
			"CREATE TABLE " + TABLE_RECORD +
					" (" +
					RECORD_KEY_DB_ID + " INTEGER PRIMARY KEY, " +
					RECORD_KEY_ID + " TEXT, " +
					RECORD_KEY_BASE_TYPE + " TEXT, " +
					RECORD_KEY_CREATED_AT + " DOUBLE, " +
					RECORD_KEY_CLIENT_CREATED_AT + " DOUBLE, " +
					RECORD_KEY_NONCE + " LONG, " +
					RECORD_KEY_STATE + " TEXT, " +
					RECORD_KEY_JSON + " TEXT" +
					");";

	// KeyValue TODO: Is this really necessary? There has to be a more elegant way to do this that is simpler.
	private static final String TABLE_KEYVALUE = "key_value";
	private static final String KEYVALUE_KEY_KEY = "key";     // 0
	private static final String KEYVALUE_KEY_VALUE = "value"; // 1

	private static final String TABLE_CREATE_KEYVALUE =
			"CREATE TABLE " + TABLE_KEYVALUE +
					" (" +
					KEYVALUE_KEY_KEY + " TEXT PRIMARY KEY, " +
					KEYVALUE_KEY_VALUE + " TEXT" +
					");";

	// Raw SQL
	private static final String QUERY_RECORD_GET_NEXT_TO_SEND = "SELECT * FROM " + TABLE_RECORD + " WHERE " + RECORD_KEY_STATE + " = '" + ActivityFeedItem.State.sending.name() + "' ORDER BY " + RECORD_KEY_DB_ID + " ASC LIMIT 1";

	private static final String QUERY_RECORD_GET_LAST_ID = "SELECT " + RECORD_KEY_ID + " FROM " + TABLE_RECORD + " WHERE " + RECORD_KEY_STATE + " = '" + ActivityFeedItem.State.saved + "' AND " + RECORD_KEY_ID + " NOTNULL ORDER BY " + RECORD_KEY_ID + " DESC LIMIT 1";

	private static final String QUERY_RECORD_GET_BY_LOCAL_ID = "SELECT * FROM " + TABLE_RECORD + " WHERE " + RECORD_KEY_NONCE + " = ?";

	// TODO: This returns sorted by DB ID, when we really want to sort by ID, but sort by Client Created At if ID is blank. Complicated...
	private static final String QUERY_RECORD_GET_ALL_BY_BASE_TYPE = "SELECT * FROM " + TABLE_RECORD + " WHERE " + RECORD_KEY_BASE_TYPE + " = ? ORDER BY " + RECORD_KEY_DB_ID + " ASC";

	private static final String QUERY_ITEM_GET_ALL_NONCES = "SELECT " + RECORD_KEY_NONCE + " FROM " + TABLE_RECORD;

	private static final String QUERY_KEYVALUE_BY_KEY = "SELECT " + KEYVALUE_KEY_VALUE + " FROM " + TABLE_KEYVALUE + " WHERE " + KEYVALUE_KEY_KEY + " = ?";

	public ApptentiveDatabase(Context context) {
		super(context, DATABASE_NAME, null, DATABASE_VERSION);
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL(TABLE_CREATE_RECORD);
		db.execSQL(TABLE_CREATE_KEYVALUE);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int i, int i1) {
		db.execSQL("DROP TABLE IF EXISTS " + TABLE_RECORD);
		db.execSQL("DROP TABLE IF EXISTS " + TABLE_KEYVALUE);
		onCreate(db);
	}

	/**
	 * If an item with the same nonce as an item passed in already exists, it is overwritten by the item. Otherwise
	 * a new message is added.
	 * @param activityFeedItems
	 */
	public synchronized void addOrUpdateItems(ActivityFeedItem... activityFeedItems) {
		SQLiteDatabase db = this.getWritableDatabase();

		List<String> nonces = new ArrayList<String>();
		Cursor cursor = db.rawQuery(QUERY_ITEM_GET_ALL_NONCES, null);

		while (cursor.moveToNext()) {
			nonces.add(cursor.getString(0));
		}

		db.beginTransaction();
		for (ActivityFeedItem activityFeedItem : activityFeedItems) {
			ContentValues values = new ContentValues();
			values.put(RECORD_KEY_ID, activityFeedItem.getId());
			values.put(RECORD_KEY_BASE_TYPE, activityFeedItem.getBaseType().name());
			values.put(RECORD_KEY_CREATED_AT, activityFeedItem.getCreatedAt());
			values.put(RECORD_KEY_CLIENT_CREATED_AT, activityFeedItem.getCreatedAt());
			values.put(RECORD_KEY_NONCE, activityFeedItem.getNonce());
			values.put(RECORD_KEY_STATE, activityFeedItem.getState().name());
			values.put(RECORD_KEY_JSON, activityFeedItem.toString());
			if (nonces.contains(activityFeedItem.getNonce())) {
				db.update(TABLE_RECORD, values, RECORD_KEY_NONCE + " = ?", new String[]{activityFeedItem.getNonce()});
			} else {
				db.insert(TABLE_RECORD, null, values);
			}
			// Special case for FileMessages
			if(activityFeedItem.getType().equals(ActivityFeedItem.Type.FileMessage)) {
				FileMessage fileMessage = (FileMessage) activityFeedItem;
				if(fileMessage.getLocalUri() != null) {
					putKeyValue(fileMessage.getNonce(), fileMessage.getLocalUri());
				}
			}
		}
		db.setTransactionSuccessful();
		db.endTransaction();
		db.close();
	}

	public synchronized void updateRecord(ActivityFeedItem activityFeedItem) {
		SQLiteDatabase db = this.getWritableDatabase();
		try {
			db.beginTransaction();
			ContentValues values = new ContentValues();
			values.put(RECORD_KEY_ID, activityFeedItem.getId());
			values.put(RECORD_KEY_BASE_TYPE, activityFeedItem.getBaseType().name());
			values.put(RECORD_KEY_CREATED_AT, activityFeedItem.getCreatedAt());
			values.put(RECORD_KEY_CLIENT_CREATED_AT, activityFeedItem.getClientCreatedAt());
			values.put(RECORD_KEY_STATE, activityFeedItem.getState().name());
			values.put(RECORD_KEY_JSON, activityFeedItem.toString());
			db.update(TABLE_RECORD, values, RECORD_KEY_NONCE + " = ?", new String[]{activityFeedItem.getNonce()});

			// Special case for FileMessages
			if(activityFeedItem.getType().equals(ActivityFeedItem.Type.FileMessage)) {
				FileMessage fileMessage = (FileMessage) activityFeedItem;
				if(fileMessage.getLocalUri() != null) {
					putKeyValue(fileMessage.getNonce(), fileMessage.getLocalUri());
				}
			}
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
			db.close();
		}
	}

	public synchronized void deleteAllRecords() {
		SQLiteDatabase db = this.getWritableDatabase();
		db.delete(TABLE_RECORD, "", null);
		db.close();
	}

	public ActivityFeedItem getRecordByNonce(String nonce) {
		SQLiteDatabase db = getReadableDatabase();
		Cursor cursor = db.rawQuery(QUERY_RECORD_GET_BY_LOCAL_ID, new String[]{nonce});
		ActivityFeedItem activityFeedItem = null;
		if (cursor.moveToFirst()) {
			String json = cursor.getString(7);
			String baseType = cursor.getString(2);
			activityFeedItem = RecordFactory.fromJson(json, ActivityFeedItem.BaseType.parse(baseType));

			// Special case for FileMessages
			if(activityFeedItem != null && activityFeedItem.getType().equals(ActivityFeedItem.Type.FileMessage)) {
				FileMessage fileMessage = (FileMessage) activityFeedItem;
				fileMessage.setLocalUri(getKeyValue(fileMessage.getNonce()));
			}
		}

		cursor.close();
		db.close();
		return activityFeedItem;
	}

	public synchronized ActivityFeedItem getOldestUnsentRecord() {
		SQLiteDatabase db = getReadableDatabase();
		Cursor cursor = db.rawQuery(QUERY_RECORD_GET_NEXT_TO_SEND, null);
		ActivityFeedItem activityFeedItem = null;
		if (cursor.moveToFirst()) {
			String json = cursor.getString(7);
			String baseType = cursor.getString(2);
			activityFeedItem = RecordFactory.fromJson(json, ActivityFeedItem.BaseType.parse(baseType));
		}
		cursor.close();
		db.close();
		return activityFeedItem;
	}

	public synchronized void deleteRecord(ActivityFeedItem activityFeedItem) {
		if (activityFeedItem != null) {
			SQLiteDatabase db = getWritableDatabase();
			db.delete(TABLE_RECORD, RECORD_KEY_NONCE + " = ?", new String[]{activityFeedItem.getNonce()});
			db.close();
		}
	}

	public synchronized List<Message> getAllMessages() {
		List<Message> messages = new ArrayList<Message>();
		SQLiteDatabase db = this.getReadableDatabase();

		Cursor cursor = db.rawQuery(QUERY_RECORD_GET_ALL_BY_BASE_TYPE, new String[]{ActivityFeedItem.BaseType.message.name()});

		if (cursor.moveToFirst()) {
			do {
				String json = cursor.getString(7);
				Message message = MessageFactory.fromJson(json);
				if (message == null) {
					Log.e("Error parsing Record json from database: %s", json);
					continue;
				}
				message.setDatabaseId(cursor.getLong(0));

				// Special case for FileMessages
				if(message != null && message.getType().equals(ActivityFeedItem.Type.FileMessage)) {
					FileMessage fileMessage = (FileMessage) message;
					fileMessage.setLocalUri(getKeyValue(fileMessage.getNonce()));
				}

				messages.add(message);
			} while (cursor.moveToNext());
		}
		cursor.close();
		db.close();
		return messages;
	}

	public synchronized String getLastReceivedMessageId() {
		SQLiteDatabase db = getReadableDatabase();
		Cursor cursor = db.rawQuery(QUERY_RECORD_GET_LAST_ID, null);
		String ret = null;
		if (cursor.moveToFirst()) {
			ret = cursor.getString(0);
		}
		cursor.close();
		db.close();
		return ret;
	}

	public synchronized void putKeyValue(String key, String value) {
		SQLiteDatabase db = getWritableDatabase();

		ContentValues values = new ContentValues();
		values.put(KEYVALUE_KEY_KEY, key);
		values.put(KEYVALUE_KEY_VALUE, value);

		String existingValue = getKeyValue(key);
		if(existingValue == null) {
			db.insert(TABLE_KEYVALUE, null, values);
		} else {
			db.update(TABLE_KEYVALUE, values, KEYVALUE_KEY_KEY + " = ?", new String[]{key});
		}
	}

	public synchronized String getKeyValue(String key) {
		SQLiteDatabase db = getReadableDatabase();
		Cursor cursor = db.rawQuery(QUERY_KEYVALUE_BY_KEY, new String[]{key});
		if(cursor.moveToFirst()) {
			return cursor.getString(0);
		}
		return null;
	}


	//// Features that haven't been reimplemented yet: PayloadStore section.



}

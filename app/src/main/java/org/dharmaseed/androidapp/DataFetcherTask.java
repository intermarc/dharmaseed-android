/*
 *     Dharmaseed Android app
 *     Copyright (C) 2016  Brett Bethke
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.dharmaseed.androidapp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by bbethke on 2/11/16.
 */
// This tasks will fetch the latest data from dharmaseed.org and store it in the local database
abstract class DataFetcherTask extends AsyncTask<Void, Void, Void> {

    DBManager dbManager;
    SQLiteDatabase db;
    OkHttpClient httpClient;
    NavigationActivity navigationActivity;

    public DataFetcherTask(DBManager dbManager, NavigationActivity navigationActivity) {
        this.dbManager = dbManager;
        this.db = dbManager.getWritableDatabase();
        this.httpClient = new OkHttpClient();
        this.navigationActivity = navigationActivity;
    }

    protected void updateTable(String tableName, String tableID, String apiUrl, String[] itemKeys) {

        Cursor cursor = db.rawQuery("SELECT "+DBManager.C.Edition.EDITION+" FROM "
                +DBManager.C.Edition.TABLE_NAME
                +" WHERE "+DBManager.C.Edition.TABLE+"=\""+tableName+"\""
                , null);
        cursor.moveToFirst();
        String edition = cursor.getString(cursor.getColumnIndexOrThrow(DBManager.C.Edition.EDITION));
        cursor.close();
        Log.d("DataFetcherTask", "We have "+tableName+" edition: "+edition);

        // Get the IDs (but no details) of the items we don't yet have
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("detail", "0");
        if(edition != null && (!edition.equals(""))) {
            builder.addFormDataPart("edition", edition);
        }
        Request request = new Request.Builder()
                .url("http://www.dharmaseed.org/api/1/"+apiUrl)
                .post(builder.build())
                .build();

        Log.d("DataFetcherTask", request.toString());

        try {
            Response response = httpClient.newCall(request).execute();
            if (response.isSuccessful()) {
                JSONObject json = new JSONObject(response.body().string());
                String newEdition = json.getString("edition");
                JSONArray items = json.getJSONArray("items");
                Log.d("dataFetcher", "Retrieved "+tableName+" edition "+newEdition+". New items: "+items.length());

                // Fetch new items, starting with the latest ones
                ArrayList<Integer> itemIDs = new ArrayList<>();
                for(int i = 0; i < items.length(); i++) {
                    itemIDs.add(items.getInt(i));
                }
                Collections.sort(itemIDs, Collections.<Integer>reverseOrder());

                RequestAggregator agg = new RequestAggregator(500, tableName, tableID, apiUrl);
                JSONObject itemsWithDetails;
                for(Integer id : itemIDs) {
                    itemsWithDetails = agg.addID(id);
                    if(itemsWithDetails != null) {
                        dbManager.insertItems(itemsWithDetails, tableName, itemKeys);
                        publishProgress();
                    }
                }
                while (!agg.isDone()) {
                    // Fire any last requests still in the aggregator
                    itemsWithDetails = agg.fireRequest();
                    if(itemsWithDetails != null) {
                        dbManager.insertItems(itemsWithDetails, tableName, itemKeys);
                    }
                }

                // Mark that we've successfully cached the new edition
                ContentValues values = new ContentValues();
                values.put(DBManager.C.Edition.TABLE, tableName);
                values.put(DBManager.C.Edition.EDITION, newEdition);
                db.insertWithOnConflict(DBManager.C.Edition.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                Log.i("dataFetcher", "Saved "+tableName+" edition "+newEdition);

            } else {
                Log.e("dataFetcher", "HTTP response unsuccessful, code " + response.code());
            }
        } catch (Exception e) {
            Log.e("dataFetcher", e.toString());
        }
    }

    @Override
    protected void onProgressUpdate(Void... values) {
        super.onProgressUpdate(values);
        Log.i("OPU", "Update");
        navigationActivity.refreshLayout.setRefreshing(true);
        navigationActivity.updateDisplayedData();
    }

    @Override
    protected void onPostExecute(Void values) {
        if( (navigationActivity.teacherFetcherTask == null || this == navigationActivity.teacherFetcherTask || navigationActivity.teacherFetcherTask.getStatus() == Status.FINISHED) &&
            (navigationActivity.centerFetcherTask == null || this == navigationActivity.centerFetcherTask || navigationActivity.centerFetcherTask.getStatus() == Status.FINISHED) &&
            (navigationActivity.talkFetcherTask == null || this == navigationActivity.talkFetcherTask || navigationActivity.talkFetcherTask.getStatus() == Status.FINISHED) ) {
            Log.i("OPE", "STOP REFRESHING!");
            navigationActivity.refreshLayout.setRefreshing(false);

            // Mark the successful data sync in the database
            Date now = new Date();
            ContentValues v = new ContentValues();
            v.put(DBManager.C.Edition.TABLE, DBManager.C.Edition.LAST_SYNC);
            v.put(DBManager.C.Edition.EDITION, now.getTime());
            dbManager.getWritableDatabase().insertWithOnConflict(
                    DBManager.C.Edition.TABLE_NAME, null, v,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private class RequestAggregator {
        private int size;
        private String table;
        private String tableID;
        private ArrayList<String> IDStrings;
        private ArrayList<String> failedIds; // ids that receive socket timeouts
        private String apiUrl;

        public RequestAggregator(int size, String table, String tableID, String apiUrl) {
            this.size = size;
            this.table = table;
            this.tableID = tableID;
            this.IDStrings = new ArrayList<>(size);
            this.failedIds = new ArrayList<>(size);
            this.apiUrl = apiUrl;
        }

        // Add a new ID to the request if it's not already present in the database.
        // Fire off the request if we've reached the size limit.
        // Returns the Response if the request was fired or null if the request was not fired.
        public JSONObject addID(int ID) {
            if(keyExists(ID)) {
                return null;
            } else {
                IDStrings.add(Integer.toString(ID));
                if(IDStrings.size() == size) {
                    return fireRequest();
                } else {
                    return null;
                }
            }
        }

        public boolean isDone() {
            return failedIds.isEmpty() && IDStrings.isEmpty();
        }

        public JSONObject fireRequest() {
            if (isDone()) {
                return null;
            }

            ArrayList<String> ids = getIds();

            JSONObject json = null;

            Log.d("fireRequest", "getting "+table+": "+ids);
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("items", TextUtils.join(",", ids))
                    .addFormDataPart("detail", "1")
                    .build();
            Request request = new Request.Builder()
                    .url("http://www.dharmaseed.org/api/1/"+apiUrl)
                    .post(body)
                    .build();

            boolean success = false;

            try {
                Response response = httpClient.newCall(request).execute();
                if(response.isSuccessful()) {
                    success = true;
                    json = new JSONObject(response.body().string());
                }
            } catch (Exception e) {
                Log.e("fireRequest", e.toString());
            }

            if (!success) {
                failedIds.addAll(ids);
                Log.d("fireRequest", "failed: " + failedIds.size());
            }

            // Reset state for the next request
            ids.clear();
            return json;
        }

        /**
         * Adds failed ids to IDStrings if there is room
         * @return
         */
        private ArrayList<String> getIds() {
            if (size > IDStrings.size() && !failedIds.isEmpty()) {
                int len = size - IDStrings.size();
                if (len > failedIds.size()) {
                    len = failedIds.size();
                }
                Log.d("fireReq", "re-trying " + len + " failed ids");
                List<String> idsToAdd = failedIds.subList(0, len);
                IDStrings.addAll(idsToAdd);
                idsToAdd.clear();
            }
            return IDStrings;
        }

        public boolean keyExists(int ID) {
            Cursor cursor = db.rawQuery("SELECT 1 FROM "+table+" WHERE "+ tableID +"="+ID, null);
            boolean result = cursor.getCount() == 1;
            cursor.close();
            return result;
        }
    }

}

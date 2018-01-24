/* ownCloud Android Library is available under MIT license
 *   Copyright (C) 2015 ownCloud Inc.
 *   Copyright (C) 2017 Nextcloud GmbH
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *   
 *   The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *   
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
 *   EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 *   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND 
 *   NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS 
 *   BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN 
 *   ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN 
 *   CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *   THE SOFTWARE.
 *
 */

package com.owncloud.android.lib.resources.users;

import android.text.TextUtils;

import com.owncloud.android.lib.common.OwnCloudBasicCredentials;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.Quota;
import com.owncloud.android.lib.common.UserInfo;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.status.OwnCloudVersion;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.GetMethod;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Gets information (id, display name, and e-mail address and many other things) about the user logged in.
 *
 * @author masensio
 * @author David A. Velasco
 * @author Mario Danic
 */
public class GetRemoteUserInfoOperation extends RemoteOperation {

    private static final String TAG = GetRemoteUserInfoOperation.class.getSimpleName();

    // OCS Route
    private static final String OCS_ROUTE_SELF = "/ocs/v1.php/cloud/user";
    private static final String OCS_ROUTE_SEARCH = "/ocs/v1.php/cloud/users/";

    // JSON Node names
    private static final String NODE_OCS = "ocs";
    private static final String NODE_DATA = "data";
    private static final String NODE_ID = "id";
    private static final String NODE_DISPLAY_NAME = "display-name";
    private static final String NODE_DISPLAY_NAME_ALT = "displayname";
    private static final String NODE_EMAIL = "email";
    private static final String NODE_ENABLED = "enabled";
    private static final String NODE_PHONE = "phone";
    private static final String NODE_ADDRESS = "address";
    private static final String NODE_WEBSITE = "website";
    private static final String NODE_WEBPAGE = "webpage";
    private static final String NODE_TWITTER = "twitter";

    private static final String NODE_QUOTA = "quota";
    private static final String NODE_QUOTA_FREE = "free";
    private static final String NODE_QUOTA_USED = "used";
    private static final String NODE_QUOTA_TOTAL = "total";
    private static final String NODE_QUOTA_RELATIVE = "relative";

    /**
     * Quota return value for a not computed space value.
     */
    public static final long SPACE_NOT_COMPUTED = -1;

    /**
     * Quota return value for unknown space value.
     */
    public static final long SPACE_UNKNOWN = -2;

    /**
     * Quota return value for unlimited space.
     */
    public static final long SPACE_UNLIMITED = -3;

    /**
     * Quota return value for quota information not available.
     */
    public static final long QUOTA_LIMIT_INFO_NOT_AVAILABLE = Long.MIN_VALUE;

    private String userID;

    public GetRemoteUserInfoOperation() {
    }

    public GetRemoteUserInfoOperation(String userID) {
        this.userID = userID;
    }

    @Override
    protected RemoteOperationResult run(OwnCloudClient client) {
        RemoteOperationResult result = null;
        int status = -1;
        GetMethod get = null;

        OwnCloudVersion version = client.getOwnCloudVersion();
        boolean versionWithSelfAPI = version != null && version.isSelfSupported();

        String url = "";

        if (!versionWithSelfAPI && !TextUtils.isEmpty(userID)) {
            url = client.getBaseUri() + OCS_ROUTE_SEARCH + userID;
        } else {
            url = client.getBaseUri() + OCS_ROUTE_SELF;
        }


        OwnCloudBasicCredentials credentials = (OwnCloudBasicCredentials) client.getCredentials();
        
        //Get the user
        try {

            get = new GetMethod(url);
            get.addRequestHeader(OCS_API_HEADER, OCS_API_HEADER_VALUE);
            get.setQueryString(new NameValuePair[]{new NameValuePair("format", "json")});
            status = client.executeMethod(get);

            if (isSuccess(status)) {
                String response = get.getResponseBodyAsString();
                Log_OC.d(TAG, "Successful response: " + response);

                // Parse the response
                JSONObject respJSON = new JSONObject(response);
                JSONObject respOCS = respJSON.getJSONObject(NODE_OCS);
                JSONObject respData = respOCS.getJSONObject(NODE_DATA);

                UserInfo userInfo = new UserInfo();

                // we don't really always have the ID
                if (respData.has(NODE_ID)) {
                    userInfo.setId(respData.getString(NODE_ID));
                } else {
                    if (TextUtils.isEmpty(userID)) {
                        userInfo.setId(credentials.getUsername());
                    } else {
                        userInfo.setId(userID);
                    }
                }

                // Two endpoints, two different responses
                if (respData.has(NODE_DISPLAY_NAME)) {
                    userInfo.setDisplayName(respData.getString(NODE_DISPLAY_NAME));
                } else {
                    userInfo.setDisplayName(respData.getString(NODE_DISPLAY_NAME_ALT));
                }

                if (hasData(respData, NODE_EMAIL)) {
                    userInfo.setEmail(respData.getString(NODE_EMAIL));
                }

                if (respData.has(NODE_QUOTA) && !respData.isNull(NODE_QUOTA)) {
                    JSONObject quota = respData.getJSONObject(NODE_QUOTA);
                    final Long quotaFree = quota.getLong(NODE_QUOTA_FREE);
                    final Long quotaUsed = quota.getLong(NODE_QUOTA_USED);
                    final Long quotaTotal = quota.getLong(NODE_QUOTA_TOTAL);
                    final Double quotaRelative = quota.getDouble(NODE_QUOTA_RELATIVE);

                    Long quotaValue;
                    try {
                        quotaValue = quota.getLong(NODE_QUOTA);
                    } catch (JSONException e) {
                        Log_OC.i(TAG, "Legacy server in use < Nextcloud 9.0.54");
                        quotaValue = QUOTA_LIMIT_INFO_NOT_AVAILABLE;
                    }

                    userInfo.setQuota(new Quota(quotaFree, quotaUsed, quotaTotal, quotaRelative, quotaValue));
                }

                if (hasData(respData, NODE_PHONE))
                    userInfo.setPhone(respData.getString(NODE_PHONE));

                if (hasData(respData, NODE_ADDRESS))
                    userInfo.setAddress(respData.getString(NODE_ADDRESS));

                // webpage is a field name pre NC12
                if (hasData(respData, NODE_WEBSITE))
                    userInfo.setWebsite(respData.getString(NODE_WEBSITE));
                else if (hasData(respData, NODE_WEBPAGE))
                    userInfo.setWebsite(respData.getString(NODE_WEBPAGE));

                if (hasData(respData, NODE_TWITTER))
                    userInfo.setTwitter(respData.getString(NODE_TWITTER));

                if (respData.has(NODE_ENABLED)) {
                    userInfo.setEnabled(respData.getBoolean(NODE_ENABLED));
                }

                // Result
                result = new RemoteOperationResult(true, get);
                // Username in result.data
                ArrayList<Object> data = new ArrayList<>();
                data.add(userInfo);
                result.setData(data);
            } else {
                result = new RemoteOperationResult(false, get);
                String response = get.getResponseBodyAsString();
                Log_OC.e(TAG, "Failed response while getting user information ");
                if (response != null) {
                    Log_OC.e(TAG, "*** status code: " + status + " ; response message: " + response);
                } else {
                    Log_OC.e(TAG, "*** status code: " + status);
                }
            }
        } catch (Exception e) {
            result = new RemoteOperationResult(e);
            Log_OC.e(TAG, "Exception while getting OC user information", e);
        } finally {
            if (get != null) {
                get.releaseConnection();
            }
        }
        return result;
    }

    private boolean hasData(JSONObject respData, String objectName) throws JSONException {
        return respData.has(objectName) && !respData.isNull(objectName) &&
                !TextUtils.isEmpty(respData.getString(objectName));
    }

    private boolean isSuccess(int status) {
        return (status == HttpStatus.SC_OK);
    }
}

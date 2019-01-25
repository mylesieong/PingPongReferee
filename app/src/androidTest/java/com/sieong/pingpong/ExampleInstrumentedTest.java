package com.sieong.pingpong;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.text.TextUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {




    @Test
    public void useAppContext()  {
        Context appContext = InstrumentationRegistry.getTargetContext();
        assertEquals("com.sieong.pingpong", appContext.getPackageName());
    }

    @Test
    public void testInternet() {
        OkHttpClient client = new OkHttpClient();
        Request.Builder builder = new Request.Builder();
        builder.url("https://reqres.in/api/users/2");
        Request request = builder.build();
        String result = null;
        try {
            Response response = client.newCall(request).execute();
            result = response.body().string();
        }catch (IOException e){
            e.printStackTrace();
        }
        assertTrue(!TextUtils.isEmpty(result));
    }
}

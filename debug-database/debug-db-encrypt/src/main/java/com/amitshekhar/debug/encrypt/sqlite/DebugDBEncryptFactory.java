package com.amitshekhar.debug.encrypt.sqlite;

import android.content.Context;

import com.amitshekhar.sqlite.DBFactory;
import com.amitshekhar.sqlite.SQLiteDB;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

public class DebugDBEncryptFactory implements DBFactory {

    @Override
    public SQLiteDB create(Context context, String path, String password) {
        System.loadLibrary("sqlcipher");
        return new DebugEncryptSQLiteDB(SQLiteDatabase.openOrCreateDatabase(path,/* password,*/ null));
    }

}

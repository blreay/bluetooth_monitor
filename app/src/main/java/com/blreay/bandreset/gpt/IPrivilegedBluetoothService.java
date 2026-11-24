/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /tmp/bluetooth-build-tools/android-sdk/build-tools/35.0.0/aidl --lang=java -I /home/admin/git/bluetooth_monitor2/app/src/main/aidl -o /tmp/aidl-gen /home/admin/git/bluetooth_monitor2/app/src/main/aidl/com/blreay/bandreset/gpt/IPrivilegedBluetoothService.aidl
 */
package com.blreay.bandreset.gpt;
public interface IPrivilegedBluetoothService extends android.os.IInterface
{
  /** Default implementation for IPrivilegedBluetoothService. */
  public static class Default implements com.blreay.bandreset.gpt.IPrivilegedBluetoothService
  {
    @Override public java.lang.String setBluetoothEnabled(boolean enabled) throws android.os.RemoteException
    {
      return null;
    }
    @Override public void destroy() throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.blreay.bandreset.gpt.IPrivilegedBluetoothService
  {
    /** Construct the stub at attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.blreay.bandreset.gpt.IPrivilegedBluetoothService interface,
     * generating a proxy if needed.
     */
    public static com.blreay.bandreset.gpt.IPrivilegedBluetoothService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.blreay.bandreset.gpt.IPrivilegedBluetoothService))) {
        return ((com.blreay.bandreset.gpt.IPrivilegedBluetoothService)iin);
      }
      return new com.blreay.bandreset.gpt.IPrivilegedBluetoothService.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_setBluetoothEnabled:
        {
          boolean _arg0;
          _arg0 = (0!=data.readInt());
          java.lang.String _result = this.setBluetoothEnabled(_arg0);
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_destroy:
        {
          this.destroy();
          reply.writeNoException();
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.blreay.bandreset.gpt.IPrivilegedBluetoothService
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public java.lang.String setBluetoothEnabled(boolean enabled) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(((enabled)?(1):(0)));
          boolean _status = mRemote.transact(Stub.TRANSACTION_setBluetoothEnabled, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void destroy() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_destroy, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_setBluetoothEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_destroy = (android.os.IBinder.FIRST_CALL_TRANSACTION + 16777114);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.blreay.bandreset.gpt.IPrivilegedBluetoothService";
  public java.lang.String setBluetoothEnabled(boolean enabled) throws android.os.RemoteException;
  public void destroy() throws android.os.RemoteException;
}

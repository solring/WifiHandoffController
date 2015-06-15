package com.aleph.mtk.btchannel;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Created by MTK07942 on 6/10/2015.
 */
public class ClientListAdapter extends BaseAdapter {

    private ArrayList<APClient> clientList = new ArrayList<APClient>();

    private LayoutInflater mInflater;
    private Activity mActivity;

    public ClientListAdapter(Activity context){
        mActivity = context;
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    public synchronized void updateListView(){
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                notifyDataSetChanged();
            }
        });
    }

    public void addItems(ArrayList<APClient> list){
        clientList.clear();
        clientList.addAll(list);
    }

    public void clear(){
        clientList.clear();
    }

    public void addItem(String _mac, String _ip){
        for(APClient c : clientList){
            if(c.mac.equals(_mac)) return;
        }
        clientList.add(new APClient(_mac, _ip));
    }

    @Override
    public int getCount() {
        return clientList.size();
    }

    @Override
    public Object getItem(int i) {
        return clientList.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    @Override
    public View getView(int i, View view, ViewGroup viewGroup) {
        ViewHolder holder = null;

        if (view == null) {

            view = mInflater.inflate(R.layout.list_item, null);
            holder = new ViewHolder();
            holder.tv_ip = (TextView) view.findViewById(R.id.text_ip);
            holder.tv_mac = (TextView) view.findViewById(R.id.text_mac);
            view.setTag(holder);
        } else {
            holder = (ViewHolder) view.getTag();
        }
        holder.tv_ip.setText(clientList.get(i).ip);
        holder.tv_mac.setText(clientList.get(i).mac);

        return view;
    }

    protected static class APClient{
        public String ip;
        public String mac;

        public APClient(String _mac, String _ip){
            ip = _ip;
            mac = _mac;
        }
    }
    
    public static class ViewHolder {
        public TextView tv_mac;
        public TextView tv_ip;
    }
    
}

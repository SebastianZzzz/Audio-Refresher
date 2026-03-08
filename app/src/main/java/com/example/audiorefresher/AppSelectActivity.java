package com.example.audiorefresher;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppSelectActivity extends AppCompatActivity {

    private Set<String> selectedPkgs;
    private List<AppInfo> appList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_select);

        // 读取已保存的配置
        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        selectedPkgs = new HashSet<>(prefs.getStringSet("target_pkgs", new HashSet<>()));

        loadApps();

        RecyclerView rv = findViewById(R.id.rv_app_list);
        rv.setLayoutManager(new LinearLayoutManager(this));
        AppAdapter adapter = new AppAdapter();
        rv.setAdapter(adapter);

        findViewById(R.id.btn_save_selection).setOnClickListener(v -> {
            prefs.edit().remove("target_pkgs").apply();
            prefs.edit().putStringSet("target_pkgs", selectedPkgs).apply();
            finish();
        });
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);

        for (ResolveInfo info : resolveInfos) {
            String pkg = info.activityInfo.packageName;
            appList.add(new AppInfo(
                    info.loadLabel(pm).toString(),
                    pkg,
                    info.loadIcon(pm),
                    selectedPkgs.contains(pkg)
            ));
        }

        // 排序：已选置顶 + 字母排序
        Collections.sort(appList, (a, b) -> {
            if (a.isSelected != b.isSelected) return a.isSelected ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
    }

    // 数据模型
    class AppInfo {
        String name, pkg;
        Drawable icon;
        boolean isSelected;
        AppInfo(String n, String p, Drawable i, boolean s) {
            name = n; pkg = p; icon = i; isSelected = s;
        }
    }

    // 适配器
    class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppInfo info = appList.get(position);
            holder.tvName.setText(info.name);
            holder.tvPkg.setText(info.pkg);
            holder.ivIcon.setImageDrawable(info.icon);
            holder.cb.setChecked(selectedPkgs.contains(info.pkg));
            holder.itemView.setOnClickListener(v -> {
                if (selectedPkgs.contains(info.pkg)) {
                    selectedPkgs.remove(info.pkg);
                } else {
                    selectedPkgs.add(info.pkg);
                }
                notifyItemChanged(position);
            });
        }

        @Override
        public int getItemCount() { return appList.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivIcon; TextView tvName, tvPkg; CheckBox cb;
            ViewHolder(View v) {
                super(v);
                ivIcon = v.findViewById(R.id.iv_app_icon);
                tvName = v.findViewById(R.id.tv_app_name);
                tvPkg = v.findViewById(R.id.tv_pkg_name);
                cb = v.findViewById(R.id.cb_selected);
            }
        }
    }
}
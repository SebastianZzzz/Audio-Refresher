package com.example.audiorefresher;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppSelectActivity extends AppCompatActivity {
    private String configKey;
    private Set<String> selectedPkgs;
    private SharedPreferences prefs;
    private List<ApplicationInfo> displayApps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_select);

        // 1. 获取模式
        boolean isExtreme = getIntent().getBooleanExtra("is_extreme", false);
        configKey = isExtreme ? "extreme_pkgs" : "target_pkgs";

        // 2. 设置标题
        setTitle(isExtreme ? "选择极端模式应用" : "选择普通模式应用");

        // 尝试修改布局中的大标题文字
        TextView tvMainTitle = findViewById(R.id.tv_select_title);
        if (tvMainTitle != null) {
            tvMainTitle.setText(isExtreme ? "选择极端模式应用" : "选择普通模式应用");
        }
        prefs = getSharedPreferences("config", MODE_PRIVATE);
        selectedPkgs = new HashSet<>(prefs.getStringSet(configKey, new HashSet<>()));

        // 3. 获取应用列表
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        displayApps = new ArrayList<>();

        // 优化点：创建一个临时的 Map 来存储名字，避免在排序时重复 loadLabel
        java.util.Map<String, String> nameCache = new java.util.HashMap<>();

        for (ApplicationInfo app : allApps) {
            if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                displayApps.add(app);
                // 提前读取名字并缓存
                nameCache.put(app.packageName, app.loadLabel(pm).toString());
            }
        }

        // --- 排序逻辑 ---
        displayApps.sort((app1, app2) -> {
            boolean isSelected1 = selectedPkgs.contains(app1.packageName);
            boolean isSelected2 = selectedPkgs.contains(app2.packageName);

            if (isSelected1 && !isSelected2) return -1;
            if (!isSelected1 && isSelected2) return 1;

            // 使用缓存的名字进行比较，这样既快又是按字母排序
            String name1 = nameCache.get(app1.packageName);
            String name2 = nameCache.get(app2.packageName);
            return name1.compareToIgnoreCase(name2);
        });

        // 4. 初始化 RecyclerView
        RecyclerView recyclerView = findViewById(R.id.rv_app_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        AppAdapter adapter = new AppAdapter();
        recyclerView.setAdapter(adapter);

        // 5. 保存按钮
        Button btnSave = findViewById(R.id.btn_save_selection);
        btnSave.setOnClickListener(v -> {
            prefs.edit().putStringSet(configKey, selectedPkgs).apply();
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    // 内部适配器：匹配你现有的 item_app.xml
    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // 引用你现有的 item_app 布局
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ApplicationInfo app = displayApps.get(position);
            PackageManager pm = getPackageManager();

            holder.tvAppName.setText(app.loadLabel(pm).toString());
            holder.tvPkgName.setText(app.packageName);
            holder.ivIcon.setImageDrawable(app.loadIcon(pm));

            // 核心：处理勾选状态
            boolean isChecked = selectedPkgs.contains(app.packageName);
            holder.checkBox.setChecked(isChecked);

            // 点击整行切换状态
            holder.itemView.setOnClickListener(v -> {
                if (selectedPkgs.contains(app.packageName)) {
                    selectedPkgs.remove(app.packageName);
                } else {
                    selectedPkgs.add(app.packageName);
                }
                notifyItemChanged(position); // 刷新当前行
            });
        }

        @Override
        public int getItemCount() {
            return displayApps.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView tvAppName, tvPkgName;
            CheckBox checkBox;

            ViewHolder(View itemView) {
                super(itemView);
                // 匹配 item_app.xml 中的 ID
                ivIcon = itemView.findViewById(R.id.iv_app_icon);
                tvAppName = itemView.findViewById(R.id.tv_app_name);
                tvPkgName = itemView.findViewById(R.id.tv_pkg_name);
                checkBox = itemView.findViewById(R.id.cb_selected); // 注意这里是 cb_selected
            }
        }
    }
}
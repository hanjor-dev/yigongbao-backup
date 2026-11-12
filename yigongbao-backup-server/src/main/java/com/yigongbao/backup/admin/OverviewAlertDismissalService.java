package com.yigongbao.backup.admin;

import com.yigongbao.backup.common.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OverviewAlertDismissalService {
    private final OverviewAlertDismissalMapper mapper;
    private final BusinessClock clock;

    @Transactional
    public Set<String> reconcile(List<AdminOverviewView.Alert> currentAlerts) {
        return reconcile(currentAlerts,true);
    }

    @Transactional
    public Set<String> reconcile(List<AdminOverviewView.Alert> currentAlerts,boolean factsAvailable) {
        Set<String> currentKeys=new LinkedHashSet<>();
        if(currentAlerts!=null)currentAlerts.stream().map(AdminOverviewView.Alert::alertKey)
                .filter(key->key!=null&&!key.isBlank()).forEach(currentKeys::add);
        Set<String> dismissedCurrent=new LinkedHashSet<>();
        for(OverviewAlertDismissalMapper.DismissalRow row:mapper.selectAll()){
            if(!factsAvailable||currentKeys.contains(row.alertKey()))dismissedCurrent.add(row.alertKey());
            else mapper.deleteByAlertKey(row.alertKey());
        }
        return Set.copyOf(dismissedCurrent);
    }

    @Transactional
    public void dismiss(AdminOverviewView.Alert alert,long actorUserId) {
        if(alert==null||alert.alertKey()==null||alert.alertKey().isBlank())
            throw new IllegalArgumentException("告警标识不能为空");
        mapper.insertIgnore(alert.alertKey(),alert.code(),alert.severity(),alert.title(),actorUserId,clock.now());
    }
}

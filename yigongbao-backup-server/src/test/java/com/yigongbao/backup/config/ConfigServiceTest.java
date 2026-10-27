package com.yigongbao.backup.config;
import org.junit.jupiter.api.Test; import static org.assertj.core.api.Assertions.*;
class ConfigServiceTest { @Test void versionedCasPreventsOverwrite(){var s=new ConfigService();var a=s.put("backup.interval","30",0,1);assertThat(a.version()).isEqualTo(1);assertThatThrownBy(()->s.put("backup.interval","60",0,2)).isInstanceOf(IllegalStateException.class);assertThat(s.put("backup.interval","60",1,2).value()).isEqualTo("60");} }

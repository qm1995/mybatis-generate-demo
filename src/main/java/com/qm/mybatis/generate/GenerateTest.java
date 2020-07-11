package com.qm.mybatis.generate;

import org.mybatis.generator.api.MyBatisGenerator;
import org.mybatis.generator.config.Configuration;
import org.mybatis.generator.config.xml.ConfigurationParser;
import org.mybatis.generator.internal.DefaultShellCallback;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class GenerateTest {

    public static void main(String[] args) {
        List<String> warnings = new ArrayList<String>();
        try {
            boolean overwrite = true;
            // 读取配置文件
            InputStream resourceAsStream = GenerateTest.class.getResourceAsStream("/mybatis-generate.xml");
            ConfigurationParser cp = new ConfigurationParser(warnings);
            Configuration config = cp.parseConfiguration(resourceAsStream);
            DefaultShellCallback callback = new DefaultShellCallback(overwrite);
            MyBatisGenerator myBatisGenerator = new MyBatisGenerator(config, callback, warnings);
            myBatisGenerator.generate(null);
        } catch (Exception e) {

            e.printStackTrace();
        }

        warnings.stream().forEach(warn -> {
            System.out.println(warn);
        });
        System.out.println("生成成功！");
    }
}

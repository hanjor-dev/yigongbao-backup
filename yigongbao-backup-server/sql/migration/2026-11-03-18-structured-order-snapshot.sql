-- 将订单列表业务字段结构化到本地快照表；嵌套对象继续使用独立 JSON 列。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE bk_order_snapshot
 ADD COLUMN public_order_code VARCHAR(64) NULL COMMENT '对外展示单号' AFTER order_code,
 ADD COLUMN order_type INT NULL COMMENT '订单类型', ADD COLUMN order_type_name VARCHAR(64) NULL COMMENT '订单类型名称',
 ADD COLUMN needs_physical_delivery INT NULL COMMENT '实体交付类型', ADD COLUMN needs_physical_delivery_name VARCHAR(64) NULL COMMENT '实体交付名称',
 ADD COLUMN business_type VARCHAR(32) NULL COMMENT '业务类型编码', ADD COLUMN business_type_name VARCHAR(128) NULL COMMENT '业务类型名称',
 ADD COLUMN org_id BIGINT NULL COMMENT '提单机构ID', ADD COLUMN org_name VARCHAR(255) NULL COMMENT '提单机构名称',
 ADD COLUMN operator_id BIGINT NULL COMMENT '操作员ID', ADD COLUMN operator_name VARCHAR(128) NULL COMMENT '操作员姓名', ADD COLUMN operator_phone VARCHAR(64) NULL COMMENT '操作员电话',
 ADD COLUMN operator_dept_id BIGINT NULL COMMENT '操作员部门ID', ADD COLUMN operator_dept_name VARCHAR(255) NULL COMMENT '操作员部门名称',
 ADD COLUMN hospital_id BIGINT NULL COMMENT '医院ID', ADD COLUMN hospital_name VARCHAR(255) NULL COMMENT '医院名称',
 ADD COLUMN area_id BIGINT NULL COMMENT '地区ID', ADD COLUMN area_name VARCHAR(255) NULL COMMENT '地区名称', ADD COLUMN full_area_name VARCHAR(512) NULL COMMENT '完整地区名称',
 ADD COLUMN hospital_dept_id BIGINT NULL COMMENT '医院科室ID', ADD COLUMN hospital_dept_name VARCHAR(255) NULL COMMENT '医院科室名称',
 ADD COLUMN doctor_id BIGINT NULL COMMENT '医生ID', ADD COLUMN doctor_name VARCHAR(128) NULL COMMENT '医生姓名', ADD COLUMN doctor_phone VARCHAR(64) NULL COMMENT '医生电话',
 ADD COLUMN patient_name VARCHAR(128) NULL COMMENT '患者姓名', ADD COLUMN patient_age INT NULL COMMENT '患者年龄', ADD COLUMN patient_gender VARCHAR(32) NULL COMMENT '患者性别编码', ADD COLUMN patient_gender_name VARCHAR(32) NULL COMMENT '患者性别名称',
 ADD COLUMN is_urgent INT NULL COMMENT '是否加急', ADD COLUMN is_postal INT NULL COMMENT '是否邮寄', ADD COLUMN postal_address VARCHAR(1024) NULL COMMENT '邮寄地址',
 ADD COLUMN designer_id BIGINT NULL COMMENT '设计师ID', ADD COLUMN designer_name VARCHAR(128) NULL COMMENT '设计师姓名', ADD COLUMN expected_delivery_date DATETIME(3) NULL COMMENT '期望交付时间',
 ADD COLUMN estimated_cost DECIMAL(18,2) NULL COMMENT '预估费用', ADD COLUMN data_evaluation_opinion TEXT NULL COMMENT '影像数据评估意见', ADD COLUMN designer_remark TEXT NULL COMMENT '设计师备注',
 ADD COLUMN phase INT NULL COMMENT '订单阶段', ADD COLUMN phase_name VARCHAR(64) NULL COMMENT '订单阶段名称', ADD COLUMN status INT NULL COMMENT '订单状态', ADD COLUMN status_name VARCHAR(128) NULL COMMENT '订单状态名称',
 ADD COLUMN status_color_json JSON NULL COMMENT '状态颜色对象', ADD COLUMN regional_audit_json JSON NULL COMMENT '区域审核对象', ADD COLUMN design_audit_json JSON NULL COMMENT '设计审核对象',
 ADD COLUMN design_start_time DATETIME(3) NULL COMMENT '设计开始时间', ADD COLUMN design_submit_time DATETIME(3) NULL COMMENT '设计提交时间',
 ADD COLUMN production_start_time DATETIME(3) NULL COMMENT '生产开始时间', ADD COLUMN production_end_time DATETIME(3) NULL COMMENT '生产结束时间',
 ADD COLUMN source_version INT NULL COMMENT '线上订单版本', ADD COLUMN rebuild_project_list_json JSON NULL COMMENT '重建项目列表';

-- 只回填旧原始快照中真实存在的数据；展示名称和嵌套对象需由后续重新备份补齐。
UPDATE bk_order_snapshot SET
 public_order_code=JSON_UNQUOTE(JSON_EXTRACT(order_raw_json,'$.publicOrderCode')),
 order_type=JSON_EXTRACT(order_raw_json,'$.orderType'), needs_physical_delivery=JSON_EXTRACT(order_raw_json,'$.needsPhysicalDelivery'),
 business_type=JSON_UNQUOTE(JSON_EXTRACT(order_raw_json,'$.businessType')),
 org_id=JSON_EXTRACT(order_raw_json,'$.orgId'), org_name=JSON_UNQUOTE(JSON_EXTRACT(order_raw_json,'$.orgName')),
 operator_id=JSON_EXTRACT(order_raw_json,'$.operatorId'), operator_name=JSON_UNQUOTE(JSON_EXTRACT(order_raw_json,'$.operatorName')),
 hospital_id=JSON_EXTRACT(order_raw_json,'$.hospitalId'), hospital_name=JSON_UNQUOTE(JSON_EXTRACT(order_raw_json,'$.hospitalName')),
 doctor_id=JSON_EXTRACT(order_raw_json,'$.doctorId'), doctor_name=JSON_UNQUOTE(JSON_EXTRACT(order_raw_json,'$.doctorName')),
 patient_name=JSON_UNQUOTE(JSON_EXTRACT(order_raw_json,'$.patientName')),
 designer_id=JSON_EXTRACT(order_raw_json,'$.designerId'), designer_name=JSON_UNQUOTE(JSON_EXTRACT(order_raw_json,'$.designerName')),
 phase=JSON_EXTRACT(order_raw_json,'$.phase'), status=JSON_EXTRACT(order_raw_json,'$.status'), source_version=JSON_EXTRACT(order_raw_json,'$.version')
WHERE order_raw_json IS NOT NULL;

CREATE INDEX idx_bk_snapshot_public_code ON bk_order_snapshot(public_order_code);
CREATE INDEX idx_bk_snapshot_created ON bk_order_snapshot(order_created_at,id);
CREATE INDEX idx_bk_snapshot_org ON bk_order_snapshot(org_id,order_created_at);
CREATE INDEX idx_bk_snapshot_hospital ON bk_order_snapshot(hospital_id,order_created_at);
CREATE INDEX idx_bk_snapshot_business ON bk_order_snapshot(business_type,phase,status);
CREATE INDEX idx_bk_snapshot_patient ON bk_order_snapshot(patient_name);
CREATE INDEX idx_bk_snapshot_doctor ON bk_order_snapshot(doctor_name);

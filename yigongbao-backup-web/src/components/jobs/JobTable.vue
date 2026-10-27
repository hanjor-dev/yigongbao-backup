<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
const { jobs, selectedJobId, jobProgress, loadJobs, selectJob, cancelJob, retryJob } = useBackupContext();
const terminalStates = ['SUCCEEDED','FAILED','CANCELLED','CANCELED'];
const jobState = (job) => job.state || job.status || '';
</script>
<template>
  <div class="table-wrap"><table class="jobs-table"><thead><tr><th>任务编号</th><th>状态</th><th>进度</th><th>订单</th><th>文件</th><th>失败原因</th><th>操作</th></tr></thead><tbody><tr v-for="j in jobs" :key="j.id" :class="{selected:String(selectedJobId)===String(j.id)}" @click="selectJob(j)"><td class="mono">{{j.id}}</td><td><status-badge :value="jobState(j)" /></td><td><div class="progress"><span :style="{width:jobProgress(j)+'%'}"></span></div><small>{{jobProgress(j)}}%</small></td><td>{{j.orderCompleted || 0}} / {{j.orderTotal || 0}}</td><td>{{j.fileCompleted || 0}} / {{j.fileTotal || 0}}</td><td class="error-text">{{j.failureCode || '-'}}<span v-if="j.failureEvidence"> · {{j.failureEvidence}}</span></td><td><button @click.stop="selectJob(j)">详情</button><button v-if="!terminalStates.includes(jobState(j))" @click.stop="cancelJob(j)">取消</button><button v-if="terminalStates.includes(jobState(j)) && jobState(j) !== 'SUCCEEDED'" @click.stop="retryJob(j)">重试</button></td></tr></tbody></table><empty-state v-if="!jobs.length" message="暂无任务" /></div>
</template>

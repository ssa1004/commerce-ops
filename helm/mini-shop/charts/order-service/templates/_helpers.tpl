{{/*
chart 이름 — values.nameOverride 가 우선.
*/}}
{{- define "order-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
fullname — release-name + chart-name. release name 에 chart name 이 이미 포함되어 있으면
중복 prefix 를 피한다 (helm upstream pattern).
*/}}
{{- define "order-service.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
chart label.
*/}}
{{- define "order-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
공통 label.
*/}}
{{- define "order-service.labels" -}}
helm.sh/chart: {{ include "order-service.chart" . }}
{{ include "order-service.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: mini-shop
app.kubernetes.io/component: order
{{- end }}

{{/*
selector label — Deployment / Service selector.
*/}}
{{- define "order-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "order-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
ServiceAccount 이름.
*/}}
{{- define "order-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "order-service.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
이미지 reference. 우선순위:
  1. .Values.image.repository (full repo path)
  2. .Values.global.image.registry + "/" + chart name
tag 는:
  1. .Values.image.tag
  2. .Values.global.image.tag
  3. .Chart.AppVersion
*/}}
{{- define "order-service.image" -}}
{{- $repo := "" -}}
{{- if .Values.image.repository -}}
  {{- $repo = .Values.image.repository -}}
{{- else -}}
  {{- $repo = printf "%s/%s" .Values.global.image.registry (include "order-service.name" .) -}}
{{- end -}}
{{- $tag := .Values.image.tag | default .Values.global.image.tag | default .Chart.AppVersion -}}
{{- printf "%s:%s" $repo $tag -}}
{{- end }}

{{/*
imagePullPolicy.
*/}}
{{- define "order-service.imagePullPolicy" -}}
{{- .Values.image.pullPolicy | default .Values.global.image.pullPolicy | default "IfNotPresent" -}}
{{- end }}

{{/*
PAYMENT_URL / INVENTORY_URL fallback — umbrella 에서 비워두면 release-name 기반으로 채움.
값이 명시되어 있으면 그대로 사용.
*/}}
{{- define "order-service.paymentUrl" -}}
{{- if .Values.config.PAYMENT_URL -}}
{{- .Values.config.PAYMENT_URL -}}
{{- else -}}
{{- printf "http://%s-payment-service:80" .Release.Name -}}
{{- end -}}
{{- end }}

{{- define "order-service.inventoryUrl" -}}
{{- if .Values.config.INVENTORY_URL -}}
{{- .Values.config.INVENTORY_URL -}}
{{- else -}}
{{- printf "http://%s-inventory-service:80" .Release.Name -}}
{{- end -}}
{{- end }}

{{/*
ConfigMap 이름.
*/}}
{{- define "order-service.configMapName" -}}
{{- printf "%s-config" (include "order-service.fullname" .) -}}
{{- end }}

{{/*
Secret 이름.
*/}}
{{- define "order-service.secretName" -}}
{{- printf "%s-secret" (include "order-service.fullname" .) -}}
{{- end }}

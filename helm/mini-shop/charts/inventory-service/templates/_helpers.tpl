{{- define "inventory-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "inventory-service.fullname" -}}
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

{{- define "inventory-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "inventory-service.labels" -}}
helm.sh/chart: {{ include "inventory-service.chart" . }}
{{ include "inventory-service.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: mini-shop
app.kubernetes.io/component: inventory
{{- end }}

{{- define "inventory-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "inventory-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "inventory-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "inventory-service.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "inventory-service.image" -}}
{{- $repo := "" -}}
{{- if .Values.image.repository -}}
  {{- $repo = .Values.image.repository -}}
{{- else -}}
  {{- $repo = printf "%s/%s" .Values.global.image.registry (include "inventory-service.name" .) -}}
{{- end -}}
{{- $tag := .Values.image.tag | default .Values.global.image.tag | default .Chart.AppVersion -}}
{{- printf "%s:%s" $repo $tag -}}
{{- end }}

{{- define "inventory-service.imagePullPolicy" -}}
{{- .Values.image.pullPolicy | default .Values.global.image.pullPolicy | default "IfNotPresent" -}}
{{- end }}

{{- define "inventory-service.configMapName" -}}
{{- printf "%s-config" (include "inventory-service.fullname" .) -}}
{{- end }}

{{- define "inventory-service.secretName" -}}
{{- printf "%s-secret" (include "inventory-service.fullname" .) -}}
{{- end }}

import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Form, Input, Button, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { login, register } from '../api/auth';
import { useAuthStore } from '../store/authStore';

interface AuthFormValues {
  username: string;
  password: string;
  confirmPassword?: string;
}

const getErrorMessage = (error: unknown, fallback: string) => {
  const responseData = (error as { response?: { data?: { message?: string } | string } }).response?.data;
  if (typeof responseData === 'string' && responseData) {
    return responseData;
  }
  if (responseData && typeof responseData === 'object' && responseData.message) {
    return responseData.message;
  }
  return error instanceof Error ? error.message : fallback;
};

const LoginPage = () => {
  const [loading, setLoading] = useState(false);
  const [registerMode, setRegisterMode] = useState(false);
  const [form] = Form.useForm<AuthFormValues>();
  const navigate = useNavigate();
  const { setAuth } = useAuthStore();

  const onFinish = async (values: AuthFormValues) => {
    if (registerMode && values.password !== values.confirmPassword) {
      message.error('两次输入的密码不一致');
      return;
    }

    setLoading(true);
    try {
      const result = registerMode
        ? await register({ username: values.username, password: values.password })
        : await login({ username: values.username, password: values.password });

      if (result.code === 200 && result.data) {
        message.success(registerMode ? '注册成功' : '登录成功');
        setAuth(
          result.data.token,
          result.data.refreshToken,
          result.data.user.username,
          result.data.user.role,
          result.data.user.id
        );
        navigate('/');
      } else {
        message.error(result.message || (registerMode ? '注册失败' : '登录失败'));
      }
    } catch (error) {
      message.error(getErrorMessage(error, `${registerMode ? '注册' : '登录'}失败，请检查网络连接`));
    } finally {
      setLoading(false);
    }
  };

  const toggleMode = () => {
    setRegisterMode((value) => !value);
    form.resetFields();
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-blue-50 to-indigo-100">
      <div className="bg-white rounded-lg shadow-xl p-8 w-96">
        <div className="text-center mb-8">
          <h1 className="text-3xl font-bold text-gray-800 mb-2">PaiAgent</h1>
          <p className="text-gray-600">AI Agent 流程执行面板</p>
        </div>

        <Form form={form} name="login" onFinish={onFinish} size="large">
          <Form.Item
            name="username"
            rules={[
              { required: true, message: '请输入用户名' },
              { min: 3, message: '用户名至少 3 个字符' },
            ]}
          >
            <Input prefix={<UserOutlined />} placeholder="用户名" />
          </Form.Item>

          <Form.Item
            name="password"
            rules={[
              { required: true, message: '请输入密码' },
              { min: 6, message: '密码至少 6 个字符' },
            ]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>

          {registerMode && (
            <Form.Item
              name="confirmPassword"
              dependencies={['password']}
              rules={[
                { required: true, message: '请再次输入密码' },
                ({ getFieldValue }) => ({
                  validator(_, value) {
                    if (!value || getFieldValue('password') === value) {
                      return Promise.resolve();
                    }
                    return Promise.reject(new Error('两次输入的密码不一致'));
                  },
                }),
              ]}
            >
              <Input.Password prefix={<LockOutlined />} placeholder="确认密码" />
            </Form.Item>
          )}

          <Form.Item>
            <Button type="primary" htmlType="submit" className="w-full" loading={loading}>
              {registerMode ? '注册' : '登录'}
            </Button>
          </Form.Item>
        </Form>

        <div className="text-center text-sm text-gray-500">
          {registerMode ? '已有账号？' : '没有账号？'}
          <Button type="link" onClick={toggleMode} className="px-1">
            {registerMode ? '去登录' : '注册新用户'}
          </Button>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;
